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

import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.reader.osm.OSMReader;
import com.graphhopper.routing.RouterConfig;
import com.graphhopper.routing.util.AreaIndex;
import com.graphhopper.routing.util.CustomArea;
import com.graphhopper.storage.GraphHopperStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.DateFormat;
import java.util.*;

import static com.graphhopper.util.GHUtility.readCountries;
import static com.graphhopper.util.Helper.*;

/**
 * Modified version of GraphHopper to optimize working with Postgis
 *
 * @author Phil
 * @author Robin Boldt
 */
public class GraphHopperPostgis extends GraphHopper {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final HashSet<OSMPostgisReader.EdgeAddedListener> edgeAddedListeners = new HashSet<>();
    private final Map<String, String> postgisParams = new HashMap<>();

    public GraphHopperPostgis(GraphHopperConfig ghConfig) {
        super.setOSMFile(ghConfig.getString("datareader.file", ""));
        postgisParams.put("dbtype", "postgis");
        postgisParams.put("host", ghConfig.getString("db.host", ""));
        postgisParams.put("port", ghConfig.getString("db.port", "5432"));
        postgisParams.put("schema", ghConfig.getString("db.schema", ""));
        postgisParams.put("database", ghConfig.getString("db.database", ""));
        postgisParams.put("user", ghConfig.getString("db.user", ""));
        postgisParams.put("passwd", ghConfig.getString("db.passwd", ""));
        postgisParams.put("tags_to_copy", ghConfig.getString("db.tags_to_copy", ""));
        postgisParams.put("datareader.file", ghConfig.getString("datareader.file", ""));
    }

    @Override
    protected void importOSM() {
        GraphHopperStorage ghStorage = super.getGraphHopperStorage();

        logger.info("start creating graph from " + super.getOSMFile());

        RouterConfig routerConfig = super.getRouterConfig();

        OSMReader reader = new OSMPostgisReader(ghStorage, postgisParams).setFile(_getOSMFile()).
                setElevationProvider(super.getElevationProvider()).
                setCountryRuleFactory(super.getCountryRuleFactory());
        logger.info("using " + ghStorage.toString() + ", memory:" + getMemInfo());
        try {
            reader.readGraph();
        } catch (IOException ex) {
            throw new RuntimeException("Cannot read file " + getOSMFile(), ex);
        }
        DateFormat f = createFormatter();
        ghStorage.getProperties().put("datareader.import.date", f.format(new Date()));
        if (reader.getDataDate() != null)
            ghStorage.getProperties().put("datareader.data.date", f.format(reader.getDataDate()));
    }
}
