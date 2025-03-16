package com.ecosystem.plugin.business;

import org.json.JSONObject;

import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import static com.ecosystem.worker.rest.RestAccess.getRestGeneric;

import static com.ecosystem.plugin.business.BusinessLogicValueCalc.valueCalc;
import static com.ecosystem.plugin.business.BusinessLogicOfferGroups.offerGroups;

public class BusinessLogic {

    public static JSONObject getValues(JSONObject params) throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {

        //System.out.println("1:BUSINESS LOGIC: \n" + params.toString());

        if (params.has("business_logic")) {
            String option = params.getString("business_logic");
            if (option.equals("value_calc"))
                return valueCalc(params);
            if (option.equals("offer_groups"))
                return offerGroups(params);
            if (option.equals("dowork"))
                return doWork(params);
            if (option.equals("doupdate"))
                return doUpdate(params);
            if (option.equals("api")) {
                params.put("business_logic", params.get("business_logic_params"));
                return getRestGeneric("POST:" + params.getString("business_logic_action") + " :PARAM:" + params.toString());
            }

            return params;
        }
        return params;
    }

    public static JSONObject doWork(JSONObject params) {

        return params;
    }

    public static JSONObject doUpdate(JSONObject params) {

        return params;
    }

}
