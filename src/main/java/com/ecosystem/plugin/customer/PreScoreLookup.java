package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.mongodb.client.MongoClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Perform a mongo lookup and store the results in params for subsequent usage
 */
public class PreScoreLookup extends PreScoreSuper {

    public PreScoreLookup() throws Exception {

    }

    /**
     * Pre-pre predict
     */
    public void getPrePredict() {
    }

    /**
     * getPrePredict
     * @param mongoClient The mongo connection
     * @param params The params object used to pass data through the runtime process
     * @param session The cassandra connection
     * @return params
     */
    public static JSONObject getPrePredict(MongoClient mongoClient, JSONObject params, CqlSession session) throws IOException {
        try {
            /* Get the data from the logs */
            JSONArray resultArrayContacts = getContactsLoggingDetails(mongoClient, params, true, true, false, "100", "", "");
            JSONArray resultArrayResponses = getResponseLoggingDetails(mongoClient, params, true, true, true, "100", "", "");

            /* Write the results to params to be passed through the runtime */
            JSONObject loggingDetails = new JSONObject();
            loggingDetails.put("resultArrayContacts", resultArrayContacts);
            loggingDetails.put("resultArrayResponses", resultArrayResponses);
            params.put("prescore_data_lookup",loggingDetails);
        } catch (Exception e) {
            LOGGER.error("PreScoreLookup:E001:UUID: Lookup failed, prescore data lookup not written to params." + e.getMessage());
        }
        return params;
    }

}
