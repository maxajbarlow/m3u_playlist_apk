package com.iptv.manager.model;

import org.json.JSONObject;

public class EpgEntry {
    public String nowTitle;
    public String nextTitle;
    public long nowStart;
    public long nowEnd;

    public static EpgEntry fromJson(JSONObject obj) {
        EpgEntry e = new EpgEntry();
        e.nowTitle = obj.optString("now_title", "");
        e.nextTitle = obj.optString("next_title", "");
        e.nowStart = obj.optLong("now_start", 0);
        e.nowEnd = obj.optLong("now_end", 0);
        return e;
    }
}
