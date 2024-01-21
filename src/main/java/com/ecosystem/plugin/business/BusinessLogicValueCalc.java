package com.ecosystem.plugin.business;

import com.ecosystem.plugin.customer.PostScoreGSMSummerDynamic;
import com.ecosystem.utils.DataTypeConversions;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

public class BusinessLogicValueCalc {

    private static final Logger LOGGER = LogManager.getLogger(PostScoreGSMSummerDynamic.class.getName());

    public static JSONObject valueCalc(JSONObject params) {
        /*
         Extract the data required to perform the value calc from params, in particular the offer matrix record for the
         offer from the offer matrix, the record from the feature store for the customer and the propensity
        */
        JSONObject singleOffer = new JSONObject();
        try {
            singleOffer = params.getJSONObject("singleOffer");
        } catch (Exception e) {
            LOGGER.error("BusinessLogicValueCalc:E000: Offer matrix record not found in params passed to value calc");
            params.put("bundle_value", -999);
            params.put("final_value", -999);
            params.put("modified_offer_score", -999);
            return params;
        }

        JSONObject featuresObj = new JSONObject();
        try {
            featuresObj = params.getJSONObject("featuresObj");
        } catch (Exception e) {
            LOGGER.error("BusinessLogicValueCalc:E001: Feature store record not found in params passed to value calc");
            params.put("bundle_value", -999);
            params.put("final_value", -999);
            params.put("modified_offer_score", -999);
            return params;
        }

        double p = 0;
        try {
            p = DataTypeConversions.getDouble(params, "p");
        } catch (Exception e) {
            LOGGER.error("BusinessLogicValueCalc:E002: Propensity value not found in params passed to value calc, using p = 0");
        }

        /*
         Extract the parameters to be used in the value calculation from the linked dynamic corpora
        */
        JSONObject extracted_value_calc_params = new JSONObject();
        extracted_value_calc_params.put("value_multiplier",1);
        extracted_value_calc_params.put("copcar_multiplier",1);
        extracted_value_calc_params.put("alpha_multiplier",1);
        extracted_value_calc_params.put("propensity_multiplier",1);
        extracted_value_calc_params.put("value_power",1);
        extracted_value_calc_params.put("copcar_power",1);
        extracted_value_calc_params.put("alpha_power",1);
        extracted_value_calc_params.put("propensity_power",1);

        try {
            //Get the parameters for the value calculation from the linked configurations
            JSONArray value_calc_id_corpora = (JSONArray) ((
                    (JSONObject) params.getJSONObject("preloadCorpora")
                            .get("value_calc_id")).get("data"));
            String value_calc_id = value_calc_id_corpora.getJSONObject(0).getString("value_calc_id");

            JSONObject value_calc_params_with_cruft = (JSONObject) ((
                    (JSONObject) params.getJSONObject("preloadCorpora")
                            .get("value_calc_params")).get(value_calc_id));
            JSONArray value_calc_params_array = value_calc_params_with_cruft.getJSONArray("value_calc_params");

            for (int i = 0; i < value_calc_params_array.length(); i++) {
                String value_calc_param_name = value_calc_params_array.getJSONObject(i).names().getString(0);
                JSONObject value_calc_params = value_calc_params_array.getJSONObject(i).getJSONObject(value_calc_param_name);

                if (value_calc_params.getString("type").equals("numeric")) {
                    double value_calc_param_value = DataTypeConversions.getDouble(value_calc_params, "value");
                    extracted_value_calc_params.put(value_calc_param_name,value_calc_param_value);
                }
                if (value_calc_params.getString("type").equals("offer_matrix_lookup")) {
                    String value_calc_key = value_calc_params.getString("key");
                    double value_calc_param_value = DataTypeConversions.getDouble(singleOffer, value_calc_key);
                    extracted_value_calc_params.put(value_calc_param_name,value_calc_param_value);
                }
                if (value_calc_params.getString("type").equals("parameter_from_data_source_lookup")) {
                    String value_calc_key = value_calc_params.getString("key");
                    double value_calc_param_value = DataTypeConversions.getDouble(featuresObj, value_calc_key);
                    extracted_value_calc_params.put(value_calc_param_name,value_calc_param_value);
                }
            }
        } catch (Exception e) {
            LOGGER.error("BusinessLogicValueCalc:E003: Parameters for value calculation not successfully extracted," +
                    " using 1 for all parameters");
        }

        JSONObject work = params.getJSONObject("in_params");
        double offer_value = 0.0;
        double cop_car_value = 0.0;

        if (singleOffer.has("offer_price"))
            offer_value = DataTypeConversions.getDouble(singleOffer, "offer_price");
        if (singleOffer.has("price"))
            offer_value = DataTypeConversions.getDouble(singleOffer, "price");

        double offer_matrix_alpha = DataTypeConversions.getDouble(singleOffer, "alpha");

        String cop_car = DataTypeConversions.getString(singleOffer.get("cop_car")).toLowerCase();
        if (featuresObj.has(cop_car)) {
            /* if cop_car has a null or NaN it should be set to 0.0 */
            try {
                cop_car_value = featuresObj.getDouble(cop_car);
            } catch (Exception e) {
                cop_car_value = 999.0;
                LOGGER.error("BusinessLogicValueCalc:E004: copcar calculation failed, using copcar = 999");
            }
        } else {
            cop_car_value = 999.0;
            LOGGER.error("BusinessLogicValueCalc:E004: copcar calculation failed, using copcar = 999");
        }

        double value_multiplier = extracted_value_calc_params.getDouble("value_multiplier");
        double copcar_multiplier = extracted_value_calc_params.getDouble("copcar_multiplier");
        double alpha_multiplier = extracted_value_calc_params.getDouble("alpha_multiplier");
        double propensity_multiplier = extracted_value_calc_params.getDouble("propensity_multiplier");
        double value_power = extracted_value_calc_params.getDouble("value_power");
        double copcar_power = extracted_value_calc_params.getDouble("copcar_power");
        double alpha_power = extracted_value_calc_params.getDouble("alpha_power");
        double propensity_power = extracted_value_calc_params.getDouble("propensity_power");

        double propensity_term = 0.0;
        try {
            propensity_term = propensity_multiplier * Math.pow(p,propensity_power);
        } catch (Exception e) {
            LOGGER.error("BusinessLogicValueCalc:E005: propensity calculation failed, using propensity = 0");
        }
        double value_term = -999;
        try {
            value_term = value_multiplier * Math.pow(offer_value,value_power);
        } catch (Exception e) {
            LOGGER.error("BusinessLogicValueCalc:E006: value calculation failed, using value = -999");
        }
        double cop_car_term = 999;
        try {
            cop_car_term = copcar_multiplier * Math.pow(cop_car_value,copcar_power);
        } catch (Exception e) {
            LOGGER.error("BusinessLogicValueCalc:E007: copcar calculation failed, using copcar = 999");
        }
        double alpha_term = -999;
        try {
            alpha_term = alpha_multiplier * Math.pow(offer_matrix_alpha,alpha_power);
        } catch (Exception e) {
            LOGGER.error("BusinessLogicValueCalc:E008: alpha calculation failed, using alpha = -999");
        }

        double bundle_value = ((value_term - cop_car_term) * alpha_term);
        double final_value = propensity_term * bundle_value;

        params.put("bundle_value", bundle_value);
        params.put("final_value", final_value);
        params.put("modified_offer_score", final_value);

        return params;
    }

}
