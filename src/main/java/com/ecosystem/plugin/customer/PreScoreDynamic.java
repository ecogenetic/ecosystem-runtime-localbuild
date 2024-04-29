package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.mongodb.client.MongoClient;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Add key/value to properties predictor.param.lookup to allow for contextual variable lookup:
 * dynamic_lookup: 'dynamic_lookup_just4u_v1'
 */
public class PreScoreDynamic extends PreScoreSuper {

    public PreScoreDynamic() throws Exception {

    }

    /**
     * Pre-pre predict
     */
    public void getPrePredict() {
    }

    /**
     * getPostPredict
     * example setting in properties file (look for dynamic_lookup: 'dynamic_lookup_just4u'):
     * predictor.param.lookup={predictor:'justforyou',mojo:1,database:'mongodb',db:'vodacom',table:'fs_score_all_estore_gsm_recommender_rel_1',dynamic_lookup: 'dynamic_lookup_just4u',lookup:{"value":123,"key":"msisdn"},result:{parm1:'field1', parm2:'field2'}}
     * @param params
     * @param session
     * @return
     */
    public static JSONObject getPrePredict(MongoClient mongoClient, JSONObject params, CqlSession session) throws IOException {

        if (lookupDatabase == null) return params;

        try {

            /** Get dynamic properties and add virtual variables to the feature store. */
            params = getDynamicSettings(mongoClient, params);
            params = getVirtualVariables(params);

            /** Pupulate contextual variables by default based on settings. */
            params = getPrepopulateContextualVariables(params);

        } catch (Exception e) {
            LOGGER.error("PreScoreDynamic:E001:UUID: " + params.get("uuid") + " Dynamic parameters failed: " + params.toString());
            e.printStackTrace();
        }

        return params;
    }

}
