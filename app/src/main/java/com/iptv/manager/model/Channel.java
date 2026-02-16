package com.iptv.manager.model;

import org.json.JSONObject;

public class Channel {
    public int id;
    public String channelId;
    public String name;
    public String group;
    public boolean favourite;

    // EPG fields (populated separately)
    public String epgNowTitle;
    public String epgNextTitle;
    public long epgNowStart;
    public long epgNowEnd;

    public static Channel fromJson(JSONObject obj) {
        Channel ch = new Channel();
        ch.id = obj.optInt("id", 0);
        ch.channelId = obj.optString("channel_id", "");
        ch.name = obj.optString("name", "Unknown");
        ch.group = obj.optString("group", "Ungrouped");
        ch.favourite = obj.optBoolean("favourite", false);
        return ch;
    }

    /** EPG progress 0.0–1.0, or -1 if no EPG data */
    public float getEpgProgress() {
        if (epgNowStart <= 0 || epgNowEnd <= epgNowStart) return -1f;
        long now = System.currentTimeMillis() / 1000;
        if (now < epgNowStart) return 0f;
        if (now > epgNowEnd) return 1f;
        return (float)(now - epgNowStart) / (float)(epgNowEnd - epgNowStart);
    }
}
