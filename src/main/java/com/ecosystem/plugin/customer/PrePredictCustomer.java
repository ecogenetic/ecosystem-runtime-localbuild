package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import org.json.JSONObject;

public class PrePredictCustomer {

    public PrePredictCustomer() {

    }

    /**
     * Pre-pre predict, after feature store is read and before dynamic and static corpora.
     */
    public void getPrePredict() {
    }

    /**
     * getPostPredict
     * @param params
     * @param session
     * @return
     */
    public static JSONObject getPrePredict(JSONObject params, CqlSession session) {

        /*
        Manipulate params that will be used by scoring and post-scoring
         */

        return params;
    }

}