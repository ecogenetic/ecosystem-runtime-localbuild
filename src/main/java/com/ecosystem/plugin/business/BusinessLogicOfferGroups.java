package com.ecosystem.plugin.business;

import com.ecosystem.utils.DataTypeConversions;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

public class BusinessLogicOfferGroups {
    private static final Logger LOGGER = LogManager.getLogger(BusinessLogicOfferGroups.class.getName());

    public static JSONObject offerGroups(JSONObject params) {
        /*
         Extract the data required to perform the offer group filtering from params
        */
        JSONArray sortJsonArray = new JSONArray();
        try {
            sortJsonArray = params.getJSONArray("sortJsonArray");
        } catch (Exception e) {
            LOGGER.error("BusinessLogicBusinessLogicOfferGroupsValueCalc:E000: sortJsonArray not found in params passed to offer groups");
            return params;
        }

        String offer_groups_field = "";
        try {
            offer_groups_field = params.getString("offer_groups_field");
        } catch (Exception e) {
            LOGGER.error("BusinessLogicBusinessLogicOfferGroupsValueCalc:E001: offer_groups_field not found in params passed to offer groups");
            return params;
        }

        double offer_groups_count = 1.0;
        try {
            offer_groups_count = DataTypeConversions.getDouble(params, "offer_groups_count");
        } catch (Exception e) {
            LOGGER.error("BusinessLogicBusinessLogicOfferGroupsValueCalc:E002: offer_groups_count not found in params passed to offer groups");
            return params;
        }

        JSONObject offerGroupCounts = new JSONObject();
        JSONArray filteredSortJsonArray = new JSONArray();
        String offer_group = "";
        int offerIndex = 0;
        for (int i = 0; i < sortJsonArray.length(); i++) {
            if (sortJsonArray.getJSONObject(i).has(offer_groups_field)) {
                offer_group = sortJsonArray.getJSONObject(i).getString(offer_groups_field);
                if (offerGroupCounts.has(offer_group)) {
                    offerGroupCounts.put(offer_group, offerGroupCounts.getInt(offer_group) + 1);
                } else {
                    offerGroupCounts.put(offer_group, 1);
                }
                if (offerGroupCounts.getInt(offer_group) <= offer_groups_count) {
                    filteredSortJsonArray.put(offerIndex, sortJsonArray.getJSONObject(i));
                    offerIndex = offerIndex + 1;
                }
            } else {
                filteredSortJsonArray.put(offerIndex, sortJsonArray.getJSONObject(i));
                offerIndex = offerIndex + 1;
                LOGGER.warn("BusinessLogicBusinessLogicOfferGroupsValueCalc:W001: offer_groups_field not found in " + sortJsonArray.getJSONObject(i).toString() + " adding offer to filtered array");
            }
        }
        params.put("filteredSortJsonArray", filteredSortJsonArray);
        return params;
    }
}
