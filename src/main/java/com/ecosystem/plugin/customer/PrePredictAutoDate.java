package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.utils.DateUtilities.DateFormatConverter;
import com.ecosystem.utils.DateUtilities.DateUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.ZonedDateTime;

import static com.ecosystem.utils.DateUtilities.DateFormatConverter.convertStringToDate;
import static com.ecosystem.utils.DateUtilities.DateFormatConverter.getFormattedDateTime;

/**
 * This is the same structure as PrePredictAutoDate where date and other defaults are added to the lookup store.
 */
public class PrePredictAutoDate {

    public static DateUtils dateUtils = new DateUtils();
    public static DateFormatConverter converter = new DateFormatConverter();


    public PrePredictAutoDate() {
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

        JSONObject featuresObj = params.getJSONObject("featuresObj");
        JSONArray input = params.getJSONArray("input");
        JSONArray value = params.getJSONArray("value");

        String format = "yyyy-MM-dd'T'HH:mm:ssZ";
        String todayDate = dateUtils.nowDate();

        // datetime: "2024-12-18T09:05:46.019Z"
        String mongoAttribute = "datetime";
        ZonedDateTime dateTxDate = convertStringToDate(todayDate, format);
        featuresObj.put(mongoAttribute + "_day", converter.getDayOfMonth(dateTxDate))
                    .put(mongoAttribute + "_day_of_week_no", converter.getDayOfWeek(dateTxDate))
                    .put(mongoAttribute + "_day_of_week", converter.getDayOfWeek(dateTxDate).toString())
                    .put(mongoAttribute + "_day_weekend", converter.isWeekend(dateTxDate) ? 1 : 0)
                    .put(mongoAttribute + "_month", converter.getMonthOfYear(dateTxDate).getValue())
                    .put(mongoAttribute + "_year", converter.getYear(dateTxDate))
                    .put(mongoAttribute + "_day_of_year", converter.getDayOfYear(dateTxDate))
                    .put(mongoAttribute + "_year_month",
                            converter.getYear(dateTxDate) + "-" +
                                    String.format("%02d", converter.getMonthOfYear(dateTxDate).getValue()))
                    .put(mongoAttribute + "_date", converter.getDate(dateTxDate))
                    .put(mongoAttribute + "_public_holiday", converter.isPublicHoliday(dateTxDate, ""))
                    .put(mongoAttribute + "_date_full", getFormattedDateTime(dateTxDate))
                    .put(mongoAttribute + "_epoch", converter.getEpoch(dateTxDate))
                    .put(mongoAttribute + "_week_of_month", converter.getWeekOfMonth(dateTxDate))
                    .put(mongoAttribute + "_week_of_year", converter.getWeekOfYear(dateTxDate))
                    .put(mongoAttribute + "_week_and_day",
                            converter.getWeekOfYear(dateTxDate) + "-" +
                                    converter.getDayOfWeekString(dateTxDate))
                    .put(mongoAttribute + "_time", converter.getTime(dateTxDate))
                    .put(mongoAttribute + "_hour", converter.getHour(dateTxDate))
                    .put(mongoAttribute + "_minutes", converter.getMinutes(dateTxDate))
                    .put(mongoAttribute + "_time_of_day", converter.getTimeOfDay(dateTxDate))
                    .put(mongoAttribute + "_time_eat", converter.getTimeOfDay(dateTxDate));

        for (String key : featuresObj.keySet()) {
            input.put(key);                       // Add key to input array
            value.put(featuresObj.get(key));     // Add corresponding value to values array
        }

        return params;

    }

}
