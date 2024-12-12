package New_Foreflight.Weather.Service;

import New_Foreflight.Weather.DTO.AirportWeatherResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class WeatherServiceImpl implements Weatherservice {

    @Value("${checkwx.api.url}")
    private String apiUrl;

    @Value("${checkwx.api.key}")
    private String apiKey;

    @Override
    public AirportWeatherResponse getAirportWeather(String icao) {
        String endpoint = apiUrl.replace("{station}", icao) + "?x-api-key=" + apiKey;
        RestTemplate restTemplate = new RestTemplate();
        String apiResponseJSON = restTemplate.getForObject(endpoint, String.class);


        String RawMETAR = parseRawMETARText(apiResponseJSON);
        HashMap<String, Object> SeperatedComponents = separateMetarComponents(apiResponseJSON);
        String FLightRules = getFlightRules(SeperatedComponents);

        return new AirportWeatherResponse(
                RawMETAR,
                SeperatedComponents,
                FLightRules
        );
    }


    @Override
    public String parseRawMETARText(String apiResponse) {
        return new JSONObject(apiResponse)
                .getJSONArray("data")
                .getJSONObject(0)
                .getString("raw_text");
    }

    @Override
    public HashMap<String, Object> separateMetarComponents(String info) {
        JSONObject result = new JSONObject(info)
                .getJSONArray("data")
                .getJSONObject(0);

        LinkedHashMap<String, Object> metarComponents = new LinkedHashMap<>();

        // Add METAR components using reusable methods
        addComponentIfPresent(result, "wind", metarComponents, this::parseWinds);
        addComponentIfPresent(result, "visibility", metarComponents, this::parseVisibility);
        addComponentIfPresent(result, "clouds", metarComponents, this::parseClouds);
        addComponentIfPresent(result, "temperature", metarComponents, this::parseTemperature);
        addComponentIfPresent(result, "dewpoint", metarComponents, this::parseDewpoint);
        addComponentIfPresent(result, "barometer", metarComponents, this::parsePressure);
        addComponentIfPresent(result, "humidity", metarComponents, this::parseHumidity);

        return metarComponents;
    }
    @Override
    public String getFlightRules(HashMap<String, Object> WeatherComponents) {

        /*
        * VFR conditions are defined as visibility greater than 5 statute miles and a cloud ceiling above 3,000 feet.
        *
        * MVFR conditions occur when visibility is between 3 and 5 statute miles or the cloud ceiling is between 1,000 and 3,000 feet.
        *
        * IFR conditions are for visibility less than or equal to 3 statute miles or a cloud ceiling at or below 1,000 feet.
        *
        * Complete this function to determine the flight rules for the given weather. return the result as a string E.G VFR, IFR, MVFR
        *
        * */

        System.out.println(WeatherComponents.get("clouds"));
        System.out.println(WeatherComponents.get("visibility"));


        return "test";
    }


    /**
     * Helper method to add a component if it exists in the JSON object.the "handle clouds handle vis etc.
     *
     */
    private <T> void addComponentIfPresent(JSONObject result, String key, LinkedHashMap<String, Object> map, DataParser<T> parser) {
        if (result.has(key) && !result.isNull(key)) {
            map.put(key, parser.parse(result.get(key)));
        }
    }

    // Define functional interface for reusable parsers
    @FunctionalInterface
    private interface DataParser<T> {
        T parse(Object data);
    }

    // Parsers for METAR components
    private String parseWinds(Object windDataObj) {
        JSONObject windData = (JSONObject) windDataObj;
        int direction = windData.optInt("degrees", 0);
        int speedKts = windData.optInt("speed_kts", 0);
        int gustKts = windData.optInt("gust_kts", 0);

        return gustKts > 0
                ? String.format("%d at %d-%d kts", direction, speedKts, gustKts)
                : String.format("%d at %d kts", direction, speedKts);
    }

    private String parseVisibility(Object visibilityDataObj) {
        JSONObject visibilityData = (JSONObject) visibilityDataObj;
        return visibilityData.optString("miles") + " SM";
    }


    /*
    * parses the clouds obj into a list of cloud ceilings and adds to the metar components.
    * only displays if sky conditions are not clear.
    *
    * */
    private List<HashMap<String, String>> parseClouds(Object cloudsDataObj) {
        JSONArray cloudsArray = (JSONArray) cloudsDataObj;
        List<HashMap<String, String>> cloudsList = new ArrayList<>();

        for (int i = 0; i < cloudsArray.length(); i++) {
            JSONObject cloud = cloudsArray.getJSONObject(i);
            LinkedHashMap<String, String> cloudMap = new LinkedHashMap<>();

            String skyCode = cloud.optString("code", "Unknown");
            cloudMap.put("code", skyCode);

            if (!"CLR".equalsIgnoreCase(skyCode)) {
                cloudMap.put("feet", cloud.optString("feet", "Unknown"));
            }

            cloudsList.add(cloudMap);
        }
        return cloudsList;
    }

    private String parseTemperature(Object temperatureDataObj) {
        JSONObject tempData = (JSONObject) temperatureDataObj;
        return String.format("%s degrees F, %s degrees C",
                tempData.optString("fahrenheit"),
                tempData.optString("celsius"));
    }

    private String parseDewpoint(Object dewpointDataObj) {
        JSONObject dewpointData = (JSONObject) dewpointDataObj;
        return String.format("%s degrees F, %s degrees C",
                dewpointData.optString("fahrenheit"),
                dewpointData.optString("celsius"));
    }

    private String parsePressure(Object pressureDataObj) {
        JSONObject pressureData = (JSONObject) pressureDataObj;
        return "hg: " + pressureData.optString("hg");
    }

    private String parseHumidity(Object humidityDataObj) {
        JSONObject humidityData = (JSONObject) humidityDataObj;
        return humidityData.optString("percent") + " %";
    }
}
