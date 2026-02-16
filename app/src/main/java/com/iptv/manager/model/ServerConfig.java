package com.iptv.manager.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ServerConfig {
    public List<String> servers = new ArrayList<>();
    public List<Integer> serverIds = new ArrayList<>();
    public List<String> serverStatuses = new ArrayList<>();
    public List<CredentialInfo> credentials = new ArrayList<>();
    public int activeServerIndex = 0;
    public int activeCredentialIndex = 0;
    public List<RecentChannel> recents = new ArrayList<>();
    public List<String> groupOrder = new ArrayList<>();

    public static class CredentialInfo {
        public int id;
        public String username;
        public String password;

        public static CredentialInfo fromJson(JSONObject obj) {
            CredentialInfo c = new CredentialInfo();
            c.id = obj.optInt("id", 0);
            c.username = obj.optString("username", "");
            c.password = obj.optString("password", "");
            return c;
        }
    }

    public static class RecentChannel {
        public String channelId;
        public String name;
        public String group;

        public static RecentChannel fromJson(JSONObject obj) {
            RecentChannel r = new RecentChannel();
            r.channelId = obj.optString("channel_id", "");
            r.name = obj.optString("name", "");
            r.group = obj.optString("group", "");
            return r;
        }
    }

    public static ServerConfig fromJson(JSONObject obj) {
        ServerConfig cfg = new ServerConfig();

        JSONArray srvArr = obj.optJSONArray("servers");
        if (srvArr != null) {
            for (int i = 0; i < srvArr.length(); i++) {
                cfg.servers.add(srvArr.optString(i, ""));
            }
        }

        JSONArray srvIdArr = obj.optJSONArray("server_ids");
        if (srvIdArr != null) {
            for (int i = 0; i < srvIdArr.length(); i++) {
                cfg.serverIds.add(srvIdArr.optInt(i, 0));
            }
        }

        JSONArray statusArr = obj.optJSONArray("server_statuses");
        if (statusArr != null) {
            for (int i = 0; i < statusArr.length(); i++) {
                cfg.serverStatuses.add(statusArr.optString(i, ""));
            }
        }

        JSONArray credArr = obj.optJSONArray("credentials");
        if (credArr != null) {
            for (int i = 0; i < credArr.length(); i++) {
                JSONObject c = credArr.optJSONObject(i);
                if (c != null) cfg.credentials.add(CredentialInfo.fromJson(c));
            }
        }

        JSONObject active = obj.optJSONObject("active");
        if (active != null) {
            cfg.activeServerIndex = active.optInt("server_index", 0);
            cfg.activeCredentialIndex = active.optInt("credential_index", 0);
        }

        JSONArray recentArr = obj.optJSONArray("recent");
        if (recentArr != null) {
            for (int i = 0; i < recentArr.length(); i++) {
                JSONObject r = recentArr.optJSONObject(i);
                if (r != null) cfg.recents.add(RecentChannel.fromJson(r));
            }
        }

        JSONArray groupOrderArr = obj.optJSONArray("group_order");
        if (groupOrderArr != null) {
            for (int i = 0; i < groupOrderArr.length(); i++) {
                cfg.groupOrder.add(groupOrderArr.optString(i, ""));
            }
        }

        return cfg;
    }

    public String getActiveServer() {
        if (servers.isEmpty() || activeServerIndex >= servers.size()) return null;
        return servers.get(activeServerIndex);
    }

    public int getActiveServerId() {
        if (serverIds.isEmpty() || activeServerIndex >= serverIds.size()) return -1;
        return serverIds.get(activeServerIndex);
    }

    public CredentialInfo getActiveCredential() {
        if (credentials.isEmpty() || activeCredentialIndex >= credentials.size()) return null;
        return credentials.get(activeCredentialIndex);
    }
}
