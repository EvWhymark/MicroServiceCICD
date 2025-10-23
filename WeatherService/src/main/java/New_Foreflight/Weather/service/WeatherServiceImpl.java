package New_Foreflight.Weather.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import New_Foreflight.Weather.dto.AirmetResponse;
import New_Foreflight.Weather.dto.AirportWeatherResponse;

@Service
public class WeatherServiceImpl implements WeatherService {

    @Autowired
    private WeatherServiceUtility utility;

    @Value("${checkwx.api.url}")
    private String weatherApiUrl;

    @Value("${checkwx.api.key}")
    private String weatherApiKey;

    @Value("${aviation.weather.api.url}")
    private String windsAloftApiUrl;

    @Value("${aviation.weather.api.url}")
    private String aviationWeatherUrl;

    @Override
    public AirportWeatherResponse getAirportWeather(String icao) {
        if (WeatherServiceUtility.getWeatherCache(icao) != null)
            return WeatherServiceUtility.getWeatherCache(icao);
        String endpoint = weatherApiUrl.replace("{station}", icao).replace("{key}", weatherApiKey);
        System.out.println("endpoint = " + endpoint);
        RestTemplate restTemplate = new RestTemplate();
        String apiResponseJson = restTemplate.getForObject(endpoint, String.class);

        String rawMetar = parseRawMetarText(apiResponseJson);
        HashMap<String, Object> seperatedComponents = separateMetarComponents(apiResponseJson);
        String flightRules = getFlightConditions(apiResponseJson);
        AirportWeatherResponse response = new AirportWeatherResponse(rawMetar, seperatedComponents, flightRules);

        WeatherServiceUtility.addToWeatherCache(icao, response);
        return response;
    }

    @Override
    public ArrayList<AirportWeatherResponse> getNearbyMETAR(String icao) {
        ArrayList<AirportWeatherResponse> nearby = new ArrayList<>();

        try {
            if (WeatherServiceUtility.getWeatherCache(icao) != null)
                nearby.add(WeatherServiceUtility.getWeatherCache(icao));

            int radius = 30;
            String base = weatherApiUrl.substring(0, weatherApiUrl.indexOf("metar"));
            String endpoint = base
                    + String.format("metar/%s/radius/%d/decoded?x-api-key=%s", icao, radius, weatherApiKey);

            RestTemplate restTemplate = new RestTemplate();
            String apiResponseJson = restTemplate.getForObject(endpoint, String.class);

            JSONObject root = new JSONObject(apiResponseJson);
            for (int i = 0; i < root.getJSONArray("data").length(); i++) {
                JSONObject stationObj = root.getJSONArray("data").getJSONObject(i);
                String stationIcao = stationObj.optString("icao", icao);

                // use cached if available
                if (WeatherServiceUtility.getWeatherCache(stationIcao) != null) {
                    nearby.add(WeatherServiceUtility.getWeatherCache(stationIcao));
                    continue;
                }

                // wrap single station into the same structure expected by helpers
                String stationResponseJson = "{\"data\":[" + stationObj.toString() + "]}";

                String rawMetar = parseRawMetarText(stationResponseJson);
                HashMap<String, Object> seperatedComponents = separateMetarComponents(stationResponseJson);
                String flightRules = getFlightConditions(stationResponseJson);

                AirportWeatherResponse response = new AirportWeatherResponse(rawMetar, seperatedComponents,
                        flightRules);

                // cache each airport response returned by nearest/radius call
                WeatherServiceUtility.addToWeatherCache(stationIcao, response);
                nearby.add(response);
            }
        } catch (Exception e) {
            System.err.println("getNearbyMETAR error: " + e.getMessage());
        }

        return nearby;
    }

    @Override
    public String parseRawMetarText(String apiResponse) {
        return new JSONObject(apiResponse).getJSONArray("data").getJSONObject(0).getString("raw_text");
    }

    @Override
    public HashMap<String, Object> separateMetarComponents(String info) {
        JSONObject result = new JSONObject(info).getJSONArray("data").getJSONObject(0);
        LinkedHashMap<String, Object> metarComponents = new LinkedHashMap<>();

        // Add METAR components using reusable methods
        WeatherServiceUtility.addComponentIfPresent(result, "wind", metarComponents, WeatherServiceUtility::parseWinds);
        WeatherServiceUtility.addComponentIfPresent(result, "visibility", metarComponents,
                WeatherServiceUtility::parseVisibility);
        WeatherServiceUtility.addComponentIfPresent(result, "clouds", metarComponents,
                WeatherServiceUtility::parseClouds);
        WeatherServiceUtility.addComponentIfPresent(result, "temperature", metarComponents,
                WeatherServiceUtility::parseTemperature);
        WeatherServiceUtility.addComponentIfPresent(result, "dewpoint", metarComponents,
                WeatherServiceUtility::parseDewpoint);
        WeatherServiceUtility.addComponentIfPresent(result, "barometer", metarComponents,
                WeatherServiceUtility::parsePressure);
        WeatherServiceUtility.addComponentIfPresent(result, "humidity", metarComponents,
                WeatherServiceUtility::parseHumidity);
        WeatherServiceUtility.addComponentIfPresent(result, "elevation", metarComponents,
                WeatherServiceUtility::parseElevation);
        WeatherServiceUtility.addComponentIfPresent(result, "position", metarComponents,
                WeatherServiceUtility::parsePositionString);
        metarComponents.put("density_altitude", WeatherServiceUtility.computeDensityAltitude(metarComponents));

        return metarComponents;
    }

    /*
     * VFR conditions are defined as visibility greater than 5 statute miles and a cloud ceiling above 3,000 feet.
     *
     * MVFR conditions occur when visibility is between 3 and 5 statute miles or the cloud ceiling is between 1,000 and
     * 3,000 feet.
     *
     * IFR conditions are for visibility less than or equal to 3 statute miles or a cloud ceiling at or below 1,000
     * feet.
     *
     * Returns the flight conditions from the API response as a string
     *
     */
    @Override
    public String getFlightConditions(String apiResponseJson) {
        return new JSONObject(apiResponseJson).getJSONArray("data").getJSONObject(0).getString("flight_category")
                .toString();
    }

    /**
     * Provides the winds aloft data for a given airport and altitude.
     * 
     * If a given airport does not have winds aloft data, then the data from the nearest airport is returned.
     */
    @Override
    public String getWindsAloft(String airportCode, int altitude) {
        return utility.getWindsAloftData(airportCode, altitude, new String(windsAloftApiUrl));
    }

    /**
     * Provides the winds aloft data for a given latitude, longitude, and altitude.
     * 
     * The airport from which the data is sourced is the nearest airport to the given latitude and longitude. This
     * airport is identified in the response.
     */
    @Override
    public String getWindsAloft(double latitude, double longitude, int altitude) {
        return utility.getWindsAloftData(latitude, longitude, altitude, new String(windsAloftApiUrl));
    }

    public String getPirepData(String icao, int distance, int age) {

        String baseApiUrl = aviationWeatherUrl.replaceFirst("(?i)/api/data(/.*)?(\\?.*)?$", "/api/data");
        String url = String.format("%s/pirep?id=%s&distance=%d&age=%d&format=decoded", baseApiUrl, icao, distance, age);
        System.out.println("url " + url);
        RestTemplate restTemplate = new RestTemplate();
        return restTemplate.getForObject(url, String.class);
    }

    public String getAirSigmet() {

        String baseApiUrl = aviationWeatherUrl.replaceFirst("(?i)/api/data(/.*)?(\\?.*)?$", "/api/data");
        String url = String.format("%s/airsigmet?format=json&type=sigmet", baseApiUrl);

        System.out.println("url " + url);
        RestTemplate restTemplate = new RestTemplate();
        return restTemplate.getForObject(url, String.class);
    }

    public String getWindTemp(String reigon, String forcast, String level) {
        String url = String.format("%s/windtemp?region=%s&fcst=%s&level=%s", aviationWeatherUrl, reigon, forcast,
                level);
        RestTemplate restTemplate = new RestTemplate();

        return restTemplate.getForObject(url, String.class);
    }

    public String getMetar(String airport, int hours) {
        String url = String.format("%s/metar?ids=%s&hours=%d", aviationWeatherUrl, airport, hours);

        RestTemplate restTemplate = new RestTemplate();
        return restTemplate.getForObject(url, String.class);
    }

    public String getGAirmet(int southLat, int westLon, int northLat, int eastLon) {
        String zuluTime = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);

        System.out.println("Zulu Time: " + zuluTime);
        String date = LocalDate.parse(zuluTime.substring(0, 10)).toString();
        String sT = date + "T00:00:00Z";
        String eT = LocalDate.parse(date).plusDays(1) + "T00:00:00Z";
        String apiUrl = String.format(
                "%s/dataserver?requestType=retrieve&dataSource=gairmets&startTime=%s&endTime=%s&format=xml&boundingBox=%d,%d,%d,%d",
                aviationWeatherUrl, sT, eT, southLat, westLon, northLat, eastLon);

        System.out.println(apiUrl);
        RestTemplate restTemplate = new RestTemplate();
        String xml = restTemplate.getForObject(apiUrl, String.class);

        return xml;
    }

    private double extractCelsius(String formattedString) {
        if (formattedString == null || !formattedString.contains("degrees C"))
            throw new IllegalArgumentException("Celsius value missing");
        String[] parts = formattedString.split(",");
        String celsiusPart = parts[1].trim();
        String[] tokens = celsiusPart.split(" ");

        return Double.parseDouble(tokens[0]);
    }

    public String getDewPointSpread(String icao) {
        String endpoint = weatherApiUrl.replace("{station}", icao).replace("{key}", weatherApiKey);
        RestTemplate restTemplate = new RestTemplate();
        String apiResponseJson = restTemplate.getForObject(endpoint, String.class);
        HashMap<String, Object> separatedComponents = separateMetarComponents(apiResponseJson);

        try {
            String tempString = (String) separatedComponents.get("temperature");
            String dewString = (String) separatedComponents.get("dewpoint");

            double tempC = extractCelsius(tempString);
            double dewC = extractCelsius(dewString);

            double spread = tempC - dewC;
            return String.format("Dew Point Spread: %.1f°C", spread);

        } catch (Exception e) {
            return "dew point spread N/A " + e.getMessage();
        }
    }

    @Override
    public AirmetResponse getWxAirmet(double latitude, double longitude) {
        String url = String.format("https://api.checkwx.com/airmet/lat/%f/lon/%f", latitude, longitude);
        System.out.println(url);
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", weatherApiKey);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        String apiResponseJson = response.getBody();

        // Parse the JSON response
        Integer results = parseResults(apiResponseJson);
        List<HashMap<String, Object>> airmetData = parseAirmetData(apiResponseJson);

        AirmetResponse airmetResponse = new AirmetResponse(results, airmetData);
        return airmetResponse;
    }

    private Integer parseResults(String apiResponse) {
        return new JSONObject(apiResponse).getInt("results");
    }

    private List<HashMap<String, Object>> parseAirmetData(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode dataNode = root.get("data");

            List<HashMap<String, Object>> airmetList = new ArrayList<>();
            if (dataNode.isArray()) {
                for (JsonNode node : dataNode) {
                    HashMap<String, Object> airmet = mapper.convertValue(node, HashMap.class);
                    airmetList.add(airmet);
                }
            }
            return airmetList;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
    public String getTAF(String icao) {
        String endpoint = weatherApiUrl.substring(0, weatherApiUrl.indexOf("metar"))
                .concat("taf/{station}/nearest/decoded?x-api-key={key}").replace("{station}", icao)
                .replace("{key}", weatherApiKey);

        RestTemplate restTemplate = new RestTemplate();
        String apiResponseJson = restTemplate.getForObject(endpoint, String.class);
        return apiResponseJson;
    }
}
