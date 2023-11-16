package com.ecosystem.plugin.business;

import org.json.JSONObject;

public class BusinessLogic {

    public static JSONObject getValues(JSONObject params) {

        return params;
    }


    public static void main(String[] args) {
        JSONObject params = new JSONObject();
        getValues(params);
        System.out.println(params.toString(2));
    }

}
