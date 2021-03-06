package io.github.huang_chenyu.thosedays;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.media.ExifInterface;
import android.os.Environment;
import android.util.Log;
import android.util.Pair;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;


/**
 * Created by Chiao on 2017/11/25.
 */

public class Algorithm {
    private static final String LOG_TAG = "[Algorithm]";

    private static final String SERVER_PREDICTIONS_FILE_SUFFIX = ".server_predictions.json";
    private static final String USER_REPORTED_LABELS_FILE_SUFFIX = ".user_reported_labels.json";
    private static final String UUID_DIR_PREFIX = "extrasensory.labels.";
    private static final String EXTRASENSORY_PKG_NAME = "edu.ucsd.calab.extrasensory";

    private static final String LAST_TIMESTAMP_FILE_NAME = "last_timestamp";

    private static final String PHOTO_PATH = "/DCIM/Camera/";
//    private static final String PHOTO_PATH = "/DCIM/100ANDRO/";

    private static final int TIME_INTERVAL = 60;

    private static final int SLIDING_WINDOW_SIZE = 10;

    private static final int EVENT_MIN_LENGTH = 0;

    private static final double TAG_THRESHOLD_PROB = 0.7;

    private Context appContext;

//    private static final int[] ACTIVITIES = { 0, 1, 2, 3, 4, 5, 6, 19, 20, 21,
//                                    22, 23, 24, 25, 26, 27, 28, 29, 33, 34, 35, 36, 37, 38, 39, 44 };
//
//    private static final int[] LOCATIONS =
//            { 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 30, 31, 32, 40, 41, 42, 43, 45 };
//
//    private static final int[] NO_NEED = {18, 46, 47, 48, 49, 50};

    // 3: Activity, 2: Locations, 1: No Need

    private static final int[] catOfAct = {3, 3, 3, 3, 3, 3, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1,
            3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1};

    private static final String[] label2Act =
            { "Lying down", "Sitting", "Walking", "Running", "Bicycling", "Sleeping", "Lab work", "In class", "In a meeting", "At work",
                    "Indoors", "Outside", "In a car", "On a bus", "Drive - I'm the driver", "Drive - I'm a passenger", "At home", "At a restaurant",
                    "Phone in pocket", "Exercise", "Cooking", "Shopping", "Strolling", "Drinking (alcohol)", "Bathing - shower", "Cleaning", "Doing laundry",
                    "Washing dishes", "Watching TV", "Surfing the internet", "At a party", "At a bar", "At the beach", "Singing", "Talking", "Computer work",
                    "Eating", "Toilet", "Grooming", "Dressing", "At the gym", "Stairs - going up", "Stairs - going down", "Elevator", "Standing", "At school",
                    "Phone in hand", "Phone in bag", "Phone on table", "With co-workers", "With friends"};

    private static File ESAFilesDir;

    private static long photoDateToEpochTime(String t) throws Exception {


//        Log.d("PhotoDate", t);
        SimpleDateFormat df = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
        df.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));

        Date date = df.parse(t);
//        Log.d("Date", date.toString());

        long epoch = date.getTime();

//        Log.d("EPOCH", String.valueOf(epoch));

//        Date date1 = new Date(epoch);
//        DateFormat dateFormat = new SimpleDateFormat("MM/dd/yyyy");
//        DateFormat timeFormat = new SimpleDateFormat("HH:mm");
//        timeFormat.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));
//        dateFormat.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));

        // Get date string and startTime string.
//        String dateStr = dateFormat.format(date1);
//        String time = timeFormat.format(date1);

//        Log.d("Transformed", dateStr +", "+time);

        return epoch/1000;

    }

    private static List<Pair<Long, String>> readPhotosFileNamesAndTimestamps() {

        try {
            String memPath = System.getenv("EXTERNAL_STORAGE");

            File dir = new File(memPath + PHOTO_PATH);

            String[] filenames = dir.list();

            List<Pair<Long, String>> timeFilenames = new ArrayList<>();

            for( String fn : filenames) {

                if (fn.toLowerCase().contains(".jpg")) {

                    ExifInterface exifInterface = new ExifInterface(memPath + PHOTO_PATH + fn);

                    String dateTimeStr = exifInterface.getAttribute(ExifInterface.TAG_DATETIME);

                    if(dateTimeStr.length()>0) {

//                        Log.d("TIME", String.valueOf(photoDateToEpochTime(dateTimeStr)));

                        long epochTime = photoDateToEpochTime(dateTimeStr);

                        Pair tmpPair = new Pair(photoDateToEpochTime(dateTimeStr), memPath + PHOTO_PATH + fn);

                        timeFilenames.add(tmpPair);
                    }
                }
            }

            // Sort with its time.
            Collections.sort(timeFilenames, new Comparator<Pair<Long, String>>() {
                @Override
                public int compare(Pair p1, Pair p2) {
                    return Long.compare((long)p1.first, (long)p2.first);
                }
            });

            return timeFilenames;

        } catch (Exception e){
            e.printStackTrace();
        }
        return null;
    }

    public static void process(Context context) {

        try {

            ESAFilesDir = getUsersFilesDirectory(context);

            List<JSONObject> files = getLabelFiles(context, ESAFilesDir);
            if (files.isEmpty()) {
                return;
            }

            List<String> schedule = raw2schedule(files);

            // Activities, # of TIME_INTERVAL
            List<Pair<String, Integer>> rleSchedule = runLength(schedule);

            // Convert RLE activities to list of human activities.
            List<HumanActivity> activities = acts2HumnActs(context, rleSchedule, files);

            // TEST CODES, USED TO VALIDATE HUMAN ACTIVITIES.
//            for(int i = 30; i < 50; i++ ) {
//                activities.get(i).printAll();
//            }

            //TODO: This line maybe removed when we only read new files, but not all.
            // For now, we're reading old data again and again, which makes DB grows badly.
            // Only used for testing in a brand new environment
//            deleteTable(context);

            // Dump data (List of Human activities) to DB.
            if(!writeToDB(context, activities)){
                throw new RuntimeException("Cannot write new data into DB.");
            }

            // Used for testing updating comments.
//            updateCommentTest(context, activities.get(0));


        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void updateCommentTest(Context context, HumanActivity act) {

        Database2 db = new Database2();
        db.openDB(context);

        // Change the comment
        act.setComments("Oh!,  Mickie you're so fun");
        db.updateComment(act);

        db.closeDB();
    }

    private static void deleteTable(Context context) {
        Database2 db = new Database2();
        db.openDB(context);
        db.delete();
        db.closeDB();
    }

    private static boolean writeToDB(Context context, List<HumanActivity> acts) {

        Database2 db = new Database2();
        db.openDB(context);

        for(int i = 0; i < acts.size(); i++) {
            db.addEntries(acts.get(i));
        }

        db.closeDB();

        return true;
    }

    private static Set<String> findTags(List<Double> probs) {

        Set<String> tags = new HashSet<>();

        for( int i = 0; i < probs.size(); i++ ) {
            if(catOfAct[i] == 3){
                // These are activities.

            } else if (catOfAct[i] == 2) {
                // Labels about locations.
                if( probs.get(i) > TAG_THRESHOLD_PROB ) {
                    tags.add(label2Act[i]);
                }
            } else {
                // Labels we don't need.
            }
        }
        return tags;
    }

    private static List<HumanActivity> acts2HumnActs (Context context, List<Pair<String, Integer>> rleAct, List<JSONObject> files) throws IOException, JSONException{
        // Fetch the timestamps and filenames of photos :
        List<Pair<Long, String>> timeToPhotoFilepath = readPhotosFileNamesAndTimestamps();

        // Fetch timestamps.
        List<Integer> timestamps = new ArrayList<>();
        for ( int i = 0; i < files.size(); i++ ) {
            timestamps.add(files.get(i).getInt("timestamp"));
        }

        long curTime = timestamps.get(0);

        int idxESD = 0;
        int idxPhoto = 0;

        List<HumanActivity> res = new ArrayList<>();

        for (int i = 0; i < rleAct.size(); i++ ) {

            // Get activity name
            String actName = rleAct.get(i).first;

            // Get Time
            int durMin = rleAct.get(i).second;
            long startTime = curTime;
            long endTime = startTime + durMin * 60;

            //Process from epoch time to date format.
            Date date = new Date(startTime*1000);
            DateFormat dateFormat = new SimpleDateFormat("MM/dd/yyyy");
            DateFormat timeFormat = new SimpleDateFormat("HH:mm");
            timeFormat.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));
            dateFormat.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));

            // Get date string and startTime string.
            String dateStr = dateFormat.format(date);
            String startTimeStr = timeFormat.format(date);

            // Get endTime String.
            date = new Date(endTime * 1000);
            String endTimeStr = timeFormat.format(date);

            // Get lat and lon
            String lat = "null", lon = "null";
            String location = "N/A";
            try {
                JSONArray locCoor = files.get(i).getJSONArray("location_lat_long");

                if (!(locCoor == null || locCoor.length() != 2)) {
                    double latitude = locCoor.getDouble(0);
                    double longitude = locCoor.getDouble(1);
                    lat = String.valueOf(latitude);
                    lon = String.valueOf(longitude);

                    // Get address from Google API
                    Geocoder geocoder;
                    List<Address> addresses;
                    geocoder = new Geocoder(context, Locale.getDefault());
                    addresses = geocoder.getFromLocation(latitude, longitude, 1);
                    // location = addresses.get(0).getAddressLine(0);

                    String tempFeatureName = addresses.get(0).getFeatureName();
					if (tempFeatureName != null && !tempFeatureName.matches("\\d+-*\\d*")) {
						location = tempFeatureName;
					}
					else {
						location = addresses.get(0).getAddressLine(0);
					}
                    Log.d(LOG_TAG, location);
                }
            } catch (JSONException e) {
                lat = "null";
                lon = "null";
            }


            // Get tags of locations.
            Set<String> tags = new HashSet<>();

            while ( idxESD < timestamps.size() && timestamps.get(idxESD) < endTime) {

                // Read from files.
                List<Double> probs = new ArrayList<>();
                JSONArray probArray = files.get(idxESD).getJSONArray("label_probs");
                for (int j = 0; j < probArray.length(); j++) {
                    probs.add(probArray.getDouble(j));
                }

                // Get the location which has the highest prob
//                Pair<Integer, Double> locRes = findMaxLoc(probs);
//                if(locRes.second > 0.7) {
//                    String maxLoc = label2Act[locRes.first];
//                    tags.add(maxLoc);
//                }
                tags.addAll(findTags(probs));

                idxESD += 1;

            }

            // Get the paths of the related photos
            Set<String> photoPaths = new HashSet<>();

            while( idxPhoto < timeToPhotoFilepath.size() && timeToPhotoFilepath.get(idxPhoto).first < endTime) {

                if(timeToPhotoFilepath.get(idxPhoto).first >= startTime) {

                    photoPaths.add(timeToPhotoFilepath.get(idxPhoto).second);
                    Log.d("DATE_WIHT_PHOTO", dateStr +", " + startTimeStr);
                    Log.d("PHOTOPATH", timeToPhotoFilepath.get(idxPhoto).second);
                }
                idxPhoto += 1;
            }

            if (photoPaths.size() != 0) {
                Log.d("END", String.valueOf(photoPaths.size()));
            }


            HumanActivity event = new HumanActivity(actName, tags, dateStr, endTimeStr, startTimeStr, lat, lon, location, "", photoPaths);

            if (durMin > EVENT_MIN_LENGTH) {

                res.add(event);

            }

            // Update curTime
            curTime  = endTime;
        }
        return res;
    }

    /**
     * Read files and determine the activity of each time interval.
     * @param files: filenames of the unprocessed data.
     * @return List<Double> probabilities of activities.
     */
    private static List<String> raw2schedule(List<JSONObject> files) throws IOException, JSONException{

        List<Integer> timestamps = new ArrayList<>();

        for ( int i = 0; i < files.size(); i++ ) {
            timestamps.add(files.get(i).getInt("timestamp"));
        }

        int curTime = timestamps.get(0);
        int endTime = timestamps.get(timestamps.size()-1);

        int idx = 0;

        MyQue<String> actQue = new MyQue<>(SLIDING_WINDOW_SIZE);

        List<String> actRes = new ArrayList<>();

        while (curTime < endTime) {

            if (curTime >= timestamps.get(idx)) {
                while (curTime >= timestamps.get(idx)) {

                    // Load the file of this timestamp
                    List<Double> probs = new ArrayList<>();

                    JSONArray probArray = files.get(idx).getJSONArray("label_probs");

                    for (int i = 0; i < probArray.length(); i++) {

                        probs.add(probArray.getDouble(i));

                    }

                    // Get index of the activity which has the highest prob
                    String maxAct = label2Act[findMaxAct(probs)];

                    // Append to myQue
                    actQue.append(maxAct);

                    // Go to next timestamp.
                    idx += 1;
                }

            } else {
                // No new data available, use the last data.
                String tmp = actQue.peek();
                actQue.append(tmp);
            }

            // Append Result
            actRes.add(actQue.findMax());

            // Update curTime
            curTime += TIME_INTERVAL;
        }

        return actRes;
    }

    /**
     * Return the activity with the highest probability.
     * @param probs: Probabilities of all the labels.
     * @return int: Index of the activity with the highest prob.
     */
    private static int findMaxAct(List<Double> probs) {

        int maxAct = 0;
        double maxProb = 0;

        for( int i = 0; i < probs.size(); i++ ) {
            if(catOfAct[i] == 3){
                // These are the activities.
                if( probs.get(i) - maxProb > 0 ) {
                    maxAct = i;
                    maxProb = probs.get(i);
                }

            } else if (catOfAct[i] == 2) {
                // Labels about locations.
            } else {
                // Labels we don't need.
            }
        }
        return maxAct;
    }

    // TODO should be combined with findMaxAct function.
    /**
     * Return the location with the highest probability.
     * @param probs: Probabilities of all the labels.
     * @return int: Index of the location with the highest prob.
     */
    private static Pair<Integer, Double> findMaxLoc(List<Double> probs) {

        int maxLoc = 7;
        double maxProb = 0;

        for( int i = 0; i < probs.size(); i++ ) {
            if(catOfAct[i] == 3){
                // These are activities.

            } else if (catOfAct[i] == 2) {
                // Labels about locations.
                if( probs.get(i) - maxProb > 0 ) {
                    maxLoc = i;
                    maxProb = probs.get(i);
                }
            } else {
                // Labels we don't need.
            }
        }
        return new Pair<>(maxLoc, maxProb);
    }

    private static List<Pair<String, Integer>> runLength (List<String> acts) {

        List<Pair<String, Integer>> res = new ArrayList<>();
        
        String curAct = acts.get(0);
        int idx = 1, cnt = 1;

        while ( idx < acts.size()) {

            if (acts.get(idx).equals(curAct)) {

                cnt += 1;

            } else {

                res.add(new Pair<String, Integer>(curAct, cnt));

                // Update current activity.
                curAct = acts.get(idx);

                // Update count
                cnt = 1;

            }

            idx += 1;

        }
        res.add( new Pair<String, Integer>(curAct, cnt));

        return res;
    }

    private static List<JSONObject> getLabelFiles(Context context, File filesDir) throws IOException, JSONException{

        String[] filenames = filesDir.list();
        Arrays.sort(filenames);

        List<JSONObject> res = new ArrayList<>();

        // find the last timestamp
        Integer lastTimestamp = 0;
        File lastTimestampFile = context.getFileStreamPath(LAST_TIMESTAMP_FILE_NAME);

        if (lastTimestampFile.exists()) {
            Log.d(LOG_TAG, "Last timestamp file exists!");
            // read last timestamp
            FileInputStream fileIn = context.openFileInput(LAST_TIMESTAMP_FILE_NAME);
            InputStreamReader isr = new InputStreamReader(fileIn);
            BufferedReader bufferedReader = new BufferedReader(isr);
            String line;
            if ((line = bufferedReader.readLine()) != null) {
                lastTimestamp = Integer.parseInt(line);
            }
            isr.close();
            fileIn.close();
        }
        else {
            Log.d(LOG_TAG, "Last timestamp file does NOT exists!");
        }
        Log.d(LOG_TAG, "Last timestamp: " + lastTimestamp.toString());

        Integer n = filenames.length;
        for (int i = 0; i < n; i++) {
            String timestamp = filenames[i].substring(0, filenames[i].lastIndexOf(SERVER_PREDICTIONS_FILE_SUFFIX));
            // discard the old data
            if (Integer.parseInt(timestamp) <= lastTimestamp)
                continue;

            File file = new File(filesDir, filenames[i]);
            StringBuilder text = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(file));
            text.append(br.readLine()); // because ExtraSensory label files are one-line JSON file
            JSONObject jsonObject = new JSONObject(text.toString());
            jsonObject.put("timestamp", timestamp);
            res.add(jsonObject);
        }

        // update the last timestamp
        FileOutputStream fileOut = context.openFileOutput(LAST_TIMESTAMP_FILE_NAME , Context.MODE_PRIVATE);
        String newLastTimestamp = filenames[n - 1].substring(0, filenames[n - 1].lastIndexOf(SERVER_PREDICTIONS_FILE_SUFFIX));
        fileOut.write(newLastTimestamp.getBytes());
        fileOut.close();

        return res;
    }

    private static File getUsersFilesDirectory(Context context) throws PackageManager.NameNotFoundException {
        // Locate the ESA saved files directory, and the specific minute-example's file:
        Context extraSensoryAppContext = context.createPackageContext(EXTRASENSORY_PKG_NAME, 0);
        File esaFilesDir = extraSensoryAppContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (esaFilesDir == null) {
            Log.e(LOG_TAG, "Cannot find ExtraSensory directory.");
            return null;
        }

        // String[] filenames = esaFilesDir.list();
        String[] filenames = esaFilesDir.list(new FilenameFilter() {
            @Override
            public boolean accept(File file, String s) {
                return s.startsWith(UUID_DIR_PREFIX);
            }
        });

        // Assume there's only one user using this phone, and take first user's data
        File userEsaFilesDir = new File(extraSensoryAppContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), filenames[0]);
        if (!userEsaFilesDir.exists()) {
            Log.e(LOG_TAG, "Cannot find ExtraSensory user data.");
            return null;
        }
        else {
            Log.d(LOG_TAG, "ExtraSensory directory exists! " + esaFilesDir.getPath());
            return userEsaFilesDir;
        }
    }

}
