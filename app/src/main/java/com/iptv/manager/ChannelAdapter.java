package com.iptv.manager;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.iptv.manager.model.Channel;
import com.iptv.manager.model.EpgEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder> {

    private final List<Channel> allChannels = new ArrayList<>();
    private final List<Channel> filteredChannels = new ArrayList<>();
    private String currentFilter = "favourites";
    private String currentGroup = null;
    private String searchQuery = "";

    private OnChannelActionListener listener;

    public interface OnChannelActionListener {
        void onChannelClick(Channel channel);
        void onChannelLongClick(Channel channel);
        void onFocusTransferToSidebar();
    }

    public void setActionListener(OnChannelActionListener listener) {
        this.listener = listener;
    }

    // ── Data ─────────────────────────────────────────────────

    public void setChannels(List<Channel> channels) {
        allChannels.clear();
        allChannels.addAll(channels);
        applyFilter();
    }

    public List<Channel> getAllChannels() {
        return allChannels;
    }

    public List<Channel> getFilteredChannels() {
        return filteredChannels;
    }

    // ── Filtering ────────────────────────────────────────────

    public void setFilter(String filter, String group) {
        currentFilter = filter;
        currentGroup = group;
        applyFilter();
    }

    public void setSearch(String query) {
        searchQuery = query != null ? query.toLowerCase().trim() : "";
        applyFilter();
    }

    public String getCurrentFilter() {
        return currentFilter;
    }

    public String getCurrentGroup() {
        return currentGroup;
    }

    private void applyFilter() {
        filteredChannels.clear();

        for (Channel ch : allChannels) {
            // Search filter
            if (!searchQuery.isEmpty() && !ch.name.toLowerCase().contains(searchQuery)) {
                continue;
            }

            switch (currentFilter) {
                case "favourites":
                    if (ch.favourite) filteredChannels.add(ch);
                    break;
                case "all":
                    filteredChannels.add(ch);
                    break;
                case "group":
                    if (currentGroup != null && currentGroup.equals(ch.group)) {
                        filteredChannels.add(ch);
                    }
                    break;
                default:
                    filteredChannels.add(ch);
                    break;
            }
        }

        notifyDataSetChanged();
    }

    // ── EPG updates ──────────────────────────────────────────

    public void updateEpg(Map<String, EpgEntry> epgMap) {
        for (Channel ch : allChannels) {
            EpgEntry entry = epgMap.get(ch.channelId);
            if (entry != null) {
                ch.epgNowTitle = entry.nowTitle;
                ch.epgNextTitle = entry.nextTitle;
                ch.epgNowStart = entry.nowStart;
                ch.epgNowEnd = entry.nowEnd;
            }
        }
        notifyDataSetChanged();
    }

    // ── Favourite toggle ─────────────────────────────────────

    public void updateFavourite(String channelId, boolean favourite) {
        for (Channel ch : allChannels) {
            if (ch.channelId.equals(channelId)) {
                ch.favourite = favourite;
                break;
            }
        }
        applyFilter();
    }

    // ── Recent channels filter ───────────────────────────────

    public void setRecentFilter(List<String> recentIds) {
        currentFilter = "recent";
        currentGroup = null;
        filteredChannels.clear();
        for (String id : recentIds) {
            for (Channel ch : allChannels) {
                if (ch.channelId.equals(id)) {
                    filteredChannels.add(ch);
                    break;
                }
            }
        }
        notifyDataSetChanged();
    }

    // ── Sidebar data helpers ─────────────────────────────────

    public int getAllCount() {
        return allChannels.size();
    }

    public int getFavouriteCount() {
        int count = 0;
        for (Channel ch : allChannels) {
            if (ch.favourite) count++;
        }
        return count;
    }

    /** Get unique groups with counts */
    public List<GroupInfo> getGroups() {
        List<GroupInfo> groups = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Channel ch : allChannels) {
            if (!seen.contains(ch.group)) {
                seen.add(ch.group);
                int count = 0;
                for (Channel c : allChannels) {
                    if (c.group.equals(ch.group)) count++;
                }
                groups.add(new GroupInfo(ch.group, count));
            }
        }
        return groups;
    }

    public static class GroupInfo {
        public final String name;
        public final int count;
        public GroupInfo(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    // ── RecyclerView.Adapter ─────────────────────────────────

    @Override
    public int getItemCount() {
        return filteredChannels.size();
    }

    @NonNull
    @Override
    public ChannelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel, parent, false);
        return new ChannelViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChannelViewHolder holder, int position) {
        Channel ch = filteredChannels.get(position);

        holder.channelName.setText(ch.name);

        // EPG
        if (ch.epgNowTitle != null && !ch.epgNowTitle.isEmpty()) {
            holder.channelEpg.setText(ch.epgNowTitle);
            holder.channelEpg.setVisibility(View.VISIBLE);
        } else {
            holder.channelEpg.setVisibility(View.GONE);
        }

        // Star
        if (ch.favourite) {
            holder.channelStar.setImageResource(R.drawable.ic_star_filled);
            holder.channelStar.setVisibility(View.VISIBLE);
        } else {
            holder.channelStar.setVisibility(View.GONE);
        }

        // Focus visual
        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                holder.channelName.setTextColor(0xFFFFFFFF);
                holder.channelEpg.setTextColor(0xFFEAEAFF);
            } else {
                holder.channelName.setTextColor(0xFFEAEAFF);
                holder.channelEpg.setTextColor(0xFFA0A0C0);
            }
        });

        // Click → play
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onChannelClick(ch);
        });

        // Long press → favourite toggle
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onChannelLongClick(ch);
            return true;
        });

        // D-pad left → sidebar
        holder.itemView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (listener != null) listener.onFocusTransferToSidebar();
                    return true;
                }
            }
            return false;
        });
    }

    static class ChannelViewHolder extends RecyclerView.ViewHolder {
        final TextView channelName;
        final TextView channelEpg;
        final ImageView channelStar;

        ChannelViewHolder(View v) {
            super(v);
            channelName = v.findViewById(R.id.channel_name);
            channelEpg = v.findViewById(R.id.channel_epg);
            channelStar = v.findViewById(R.id.channel_star);
        }
    }
}
