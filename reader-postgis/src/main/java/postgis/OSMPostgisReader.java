/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package postgis;

import com.graphhopper.coll.GHObjectIntHashMap;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.util.DistanceCalc;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.Helper;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint;
import org.geotools.data.DataStore;
import org.geotools.feature.FeatureIterator;
import org.locationtech.jts.geom.Coordinate;
import org.opengis.feature.simple.SimpleFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

import static com.graphhopper.util.DistanceCalcEarth.DIST_EARTH;
import static com.graphhopper.util.Helper.nf;
import static com.graphhopper.util.Helper.toLowerCase;

/**
 * Reads OSM data from Postgis and uses it in GraphHopper
 *
 * @author Vikas Veshishth
 * @author Philip Welch
 * @author Mario Basa
 * @author Robin Boldt
 */
public class OSMPostgisReader extends PostgisReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(OSMPostgisReader.class);

    private static final int COORD_STATE_UNKNOWN = 0;
    private static final int COORD_STATE_PILLAR = -2;
    private static final int FIRST_NODE_ID = 1;
    private final String[] tagsToCopy;
    private File roadsFile;
    private GHObjectIntHashMap<Coordinate> coordState = new GHObjectIntHashMap<>(1000, 0.7f);
    private final DistanceCalc distCalc = DIST_EARTH;
    private final HashSet<EdgeAddedListener> edgeAddedListeners = new HashSet<>();
    private int nextNodeId = FIRST_NODE_ID;
    protected long zeroCounter = 0;
    private final IntsRef tempRelFlags;

    public OSMPostgisReader(GraphHopperStorage ghStorage, Map<String, String> postgisParams) {
        super(ghStorage, postgisParams);
        String tmpTagsToCopy = postgisParams.get("tags_to_copy");
        this.roadsFile = new File(postgisParams.get("datareader.file"));
        if (tmpTagsToCopy == null || tmpTagsToCopy.isEmpty()) {
            this.tagsToCopy = new String[]{};
        } else {
            this.tagsToCopy = tmpTagsToCopy.split(",");
        }
        tempRelFlags = encodingManager.createRelationFlags();
        if (tempRelFlags.length != 2)
            throw new IllegalArgumentException("Cannot use relation flags with != 2 integers");
        // TODO relations are set empty by default, add relation handling
        tempRelFlags.ints[0] = (int) 0L;
        tempRelFlags.ints[1] = (int) 0L;
    }

    @Override
    void processJunctions() {
        DataStore dataStore = null;
        FeatureIterator<SimpleFeature> roads = null;
        int tmpJunctionCounter = 0;

        try {
            dataStore = openPostGisStore();
            roads = getFeatureIterator(dataStore, roadsFile.getName());
            HashSet<Coordinate> tmpSet = new HashSet<>();
            while (roads.hasNext()) {
                SimpleFeature road = roads.next();

                if (!acceptFeature(road)) {
                    continue;
                }

                for (Coordinate[] points : getCoords(road)) {
                    tmpSet.clear();
                    for (int i = 0; i < points.length; i++) {
                        Coordinate c = points[i];
                        c = roundCoordinate(c);

                        // don't add the same coord twice for the same edge - happens with bad geometry, i.e.
                        // duplicate coords or a road which forms a circle (e.g. roundabout)
                        if (tmpSet.contains(c))
                            continue;

                        tmpSet.add(c);

                        // skip if its already a node
                        int state = coordState.get(c);
                        if (state >= FIRST_NODE_ID) {
                            continue;
                        }

                        if (i == 0 || i == points.length - 1 || state == COORD_STATE_PILLAR) {
                            // turn into a node if its the first or last
                            // point, or already appeared in another edge
                            int nodeId = nextNodeId++;
                            coordState.put(c, nodeId);
                            saveTowerPosition(nodeId, c);
                        } else if (state == COORD_STATE_UNKNOWN) {
                            // mark it as a pillar (which may get upgraded
                            // to an edge later)
                            coordState.put(c, COORD_STATE_PILLAR);
                        }

                        if (++tmpJunctionCounter % 100_000 == 0) {
                            LOGGER.info(nf(tmpJunctionCounter) + " (junctions), junctionMap:" + nf(coordState.size())
                                    + " " + Helper.getMemInfo());
                        }
                    }
                }
            }
        } finally {
            if (roads != null) {
                roads.close();
            }
            if (dataStore != null) {
                dataStore.dispose();
            }
        }

        if (nextNodeId == FIRST_NODE_ID)
            throw new IllegalArgumentException("No data found for roads file " + roadsFile);

        LOGGER.info("Number of junction points : " + (nextNodeId - FIRST_NODE_ID));
    }

    @Override
    void processRoads() {

        DataStore dataStore = null;
        FeatureIterator<SimpleFeature> roads = null;

        int tmpEdgeCounter = 0;

        try {
            dataStore = openPostGisStore();
            roads = getFeatureIterator(dataStore, roadsFile.getName());

            while (roads.hasNext()) {
                SimpleFeature road = roads.next();

                if (!acceptFeature(road)) {
                    continue;
                }

                for (Coordinate[] points : getCoords(road)) {
                    // Parse all points in the geometry, splitting into
                    // individual GraphHopper edges
                    // whenever we find a node in the list of points
                    Coordinate startTowerPnt = null;
                    List<Coordinate> pillars = new ArrayList<Coordinate>();
                    for (Coordinate point : points) {
                        point = roundCoordinate(point);
                        if (startTowerPnt == null) {
                            startTowerPnt = point;
                        } else {
                            int state = coordState.get(point);
                            if (state >= FIRST_NODE_ID) {
                                int fromTowerNodeId = coordState.get(startTowerPnt);
                                int toTowerNodeId = state;

                                // get distance and estimated centre
                                GHPoint estmCentre = new GHPoint(
                                        0.5 * (lat(startTowerPnt) + lat(point)),
                                        0.5 * (lng(startTowerPnt) + lng(point)));
                                PointList pillarNodes = new PointList(pillars.size(), false);

                                for (Coordinate pillar : pillars) {
                                    pillarNodes.add(lat(pillar), lng(pillar));
                                }

                                double distance = getWayLength(startTowerPnt, pillars, point);
                                addEdge(fromTowerNodeId, toTowerNodeId, road, distance, estmCentre,
                                        pillarNodes);
                                startTowerPnt = point;
                                pillars.clear();

                                if (++tmpEdgeCounter % 1_000_000 == 0) {
                                    LOGGER.info(nf(tmpEdgeCounter) + " (edges) " + Helper.getMemInfo());
                                }
                            } else {
                                pillars.add(point);
                            }
                        }
                    }
                }

            }
        } finally {
            if (roads != null) {
                roads.close();
            }

            if (dataStore != null) {
                dataStore.dispose();
            }
        }
    }

    @Override
    protected void finishReading() {
        this.coordState.clear();
        this.coordState = null;
        LOGGER.info("Finished reading. Zero Counter " + nf(zeroCounter) + " " + Helper.getMemInfo());
    }

    protected double getWayLength(Coordinate start, List<Coordinate> pillars, Coordinate end) {
        double distance = 0;

        Coordinate previous = start;
        for (Coordinate point : pillars) {
            distance += distCalc.calcDist(lat(previous), lng(previous), lat(point), lng(point));
            previous = point;
        }
        distance += distCalc.calcDist(lat(previous), lng(previous), lat(end), lng(end));

        if (distance < 0.0001) {
            // As investigation shows often two paths should have crossed via one identical point
            // but end up in two very close points.
            zeroCounter++;
            distance = 0.0001;
        }

        if (Double.isNaN(distance)) {
            LOGGER.warn("Bug in OSM or GraphHopper. Illegal tower node distance " + distance + " reset to 1m, osm way " + distance);
            distance = 1;
        }

        return distance;
    }

    @Override
    public Date getDataDate() {
        return null;
    }

    public static interface EdgeAddedListener {
        void edgeAdded(ReaderWay way, EdgeIteratorState edge);
    }

    private void addEdge(int fromTower, int toTower, SimpleFeature road, double distance,
                         GHPoint estmCentre, PointList pillarNodes) {
        EdgeIteratorState edge = graph.edge(fromTower, toTower);

        long id = getOSMId(road);

        ReaderWay way = new ReaderWay(id);

        way.setTag("estimated_distance", distance);
        way.setTag("estimated_center", estmCentre);

        // Bề rộng nền lớn nhất
        Object maxWidth = road.getAttribute("berongnenl");
        if (maxWidth != null) {
            way.setTag("max_with", Double.parseDouble(maxWidth.toString()));
        }

        // Bề rộng nền nhỏ nhất
        Object minWidth = road.getAttribute("berongnenn");
        if (minWidth != null) {
            way.setTag("min_with", Double.parseDouble(minWidth.toString()));
        }

        // Quan khu
        String dValue = "0";
        Object militaryId = road.getAttribute("military_id");
        if (militaryId != null) {
            way.setTag("military_id", militaryId.toString());
        }
        // Tinh Thanh
        String provinceKey = "province_id";
        Object provinceId = road.getAttribute("province_id");
        if (provinceId != null) {
            way.setTag(provinceKey, provinceId.toString());
        }else{
            way.setTag(provinceKey, dValue);
        }
        // Quan Huyen
        String districtKey = "district_id";
        Object districtId = road.getAttribute(districtKey);
        if (districtId != null) {
            way.setTag(districtKey, districtId.toString());
        }else{
            way.setTag(districtKey, dValue);
        }
        // Xa
        String communeKey = "commune_id";
        Object communeId = road.getAttribute("commune_id");
        if (communeId != null) {
            way.setTag(communeKey, communeId.toString());
        }else{
            way.setTag(communeKey, dValue);
        }
        // Loại đường bộ
        Object highwayType = road.getAttribute("loaiduongb");
        String highway = "primary";
        if (highwayType != null)
            switch (highwayType.toString()) {
                case "1":
                    highway = "motorway";
                    break;
                case "2":
                    highway = "primary";
                    break;
                case "3":
                    highway = "secondary";
                    break;
                case "4":
                case "5":
                    highway = "tertiary";
                    break;
                case "7":
                    highway = "unclassified";
                    break;
                case "6":
                    highway = "residential";
                    break;
                default:
                    break;
            }
        if (!highway.isEmpty()) {
            way.setTag("highway", highway);
        }

        // read maxspeed filtering for 0 which for Geofabrik shapefiles appears
        // to correspond to no tag
        Object maxSpeed = road.getAttribute("maxspeed");
        if (maxSpeed != null && !maxSpeed.toString().trim().equals("0")) {
            way.setTag("maxspeed", maxSpeed.toString());
        }

        for (String tag : tagsToCopy) {
            Object val = road.getAttribute(tag);
            if (val != null) {
                way.setTag(tag, val);
            } else {
                way.setTag(tag, "không tên");
            }
        }

        // read oneway
        Object oneway = road.getAttribute("oneway");
        if (oneway != null) {
            // Geofabrik is using an odd convention for oneway field in
            // shapefile.
            // We map back to the standard convention so that tag can be dealt
            // with correctly by the flag encoder.
            String val = toLowerCase(oneway.toString().trim());
            if (val.equals("b")) {
                // both ways
                val = "no";
            } else if (val.equals("t")) {
                // one way against the direction of digitisation
                val = "-1";
            } else if (val.equals("f")) {
                // one way Forward in the direction of digitisation
                val = "yes";
            } else {
                throw new RuntimeException("Unrecognised value of oneway field \"" + val
                        + "\" found in road with OSM id " + id);
            }

            way.setTag("oneway", val);
        }

        // Process the flags using the encoders
        EncodingManager.AcceptWay acceptWay = new EncodingManager.AcceptWay();
        if (!encodingManager.acceptWay(way, acceptWay)) {
            return;
        }

        IntsRef edgeFlags = encodingManager.handleWayTags(way, acceptWay, tempRelFlags);
        if (edgeFlags.isEmpty())
            return;

        edge.setDistance(distance);
        edge.setFlags(edgeFlags);
        edge.setWayGeometry(pillarNodes);
        encodingManager.applyWayTags(way, edge);

        if (edgeAddedListeners.size() > 0) {
            // check size first so we only allocate the iterator if we have
            // listeners
            for (EdgeAddedListener l : edgeAddedListeners) {
                l.edgeAdded(way, edge);
            }
        }
    }

    private long getOSMId(SimpleFeature road) {
        try {
            long id = Long.parseLong(road.getAttribute("osm_id").toString());
            return id;
        } catch (Exception e) {
            return 0;
        }
    }

    private Coordinate roundCoordinate(Coordinate c) {
        c.x = Helper.round6(c.x);
        c.y = Helper.round6(c.y);

        if (!Double.isNaN(c.z))
            c.z = Helper.round6(c.z);

        return c;
    }

    public void addListener(EdgeAddedListener l) {
        edgeAddedListeners.add(l);
    }
}
