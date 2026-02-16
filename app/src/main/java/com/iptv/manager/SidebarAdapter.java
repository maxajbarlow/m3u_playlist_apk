package com.iptv.manager;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for native Android TV sidebar.
 * Three view types: search bar, menu items, and dividers.
 */
public class SidebarAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    static final int TYPE_SEARCH = 0;
    static final int TYPE_ITEM = 1;
    static final int TYPE_DIVIDER = 2;

    private final List<SidebarItem> items = new ArrayList<>();
    private OnSidebarActionListener listener;
    private OnSearchListener searchListener;
    private int selectedPosition = -1;
    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable debounceRunnable;

    // ── Data model ──────────────────────────────────────────────

    public static class SidebarItem {
        int viewType;
        int iconResId;
        String label;
        String badge;
        String actionType; // all, favourites, recent, group, server, credential, manage, password, admin, logout
        String actionData; // group name, server label, etc.
        boolean isSelected;

        static SidebarItem search() {
            SidebarItem item = new SidebarItem();
            item.viewType = TYPE_SEARCH;
            return item;
        }

        static SidebarItem divider() {
            SidebarItem item = new SidebarItem();
            item.viewType = TYPE_DIVIDER;
            return item;
        }

        static SidebarItem item(int iconResId, String label, String badge, String actionType, String actionData, boolean isSelected) {
            SidebarItem item = new SidebarItem();
            item.viewType = TYPE_ITEM;
            item.iconResId = iconResId;
            item.label = label;
            item.badge = badge;
            item.actionType = actionType;
            item.actionData = actionData;
            item.isSelected = isSelected;
            return item;
        }
    }

    // ── Listeners ───────────────────────────────────────────────

    public interface OnSidebarActionListener {
        void onItemClick(SidebarItem item);
        void onFocusTransferToWebView();
    }

    public interface OnSearchListener {
        void onSearch(String query);
    }

    public void setActionListener(OnSidebarActionListener listener) {
        this.listener = listener;
    }

    public void setSearchListener(OnSearchListener listener) {
        this.searchListener = listener;
    }

    // ── Loading state ───────────────────────────────────────────

    public void setLoadingState() {
        items.clear();
        items.add(SidebarItem.search());
        items.add(SidebarItem.item(R.drawable.ic_all_channels, "Loading...", null, null, null, false));
        notifyDataSetChanged();
    }

    // ── Update items ────────────────────────────────────────────

    public void updateItems(List<SidebarItem> newItems) {
        items.clear();
        items.add(SidebarItem.search());
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    // ── Focus helpers ───────────────────────────────────────────

    /** Find the position of the selected item, or the first non-search, non-divider item */
    public int findSelectedOrFirstPosition() {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).viewType == TYPE_ITEM && items.get(i).isSelected) {
                return i;
            }
        }
        // First focusable item
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).viewType == TYPE_ITEM) {
                return i;
            }
        }
        return 0; // search box
    }

    // ── RecyclerView.Adapter ────────────────────────────────────

    @Override
    public int getItemViewType(int position) {
        return items.get(position).viewType;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_SEARCH:
                View searchView = inflater.inflate(R.layout.item_sidebar_search, parent, false);
                return new SearchViewHolder(searchView);
            case TYPE_DIVIDER:
                View dividerView = inflater.inflate(R.layout.item_sidebar_divider, parent, false);
                return new DividerViewHolder(dividerView);
            default:
                View itemView = inflater.inflate(R.layout.item_sidebar, parent, false);
                return new ItemViewHolder(itemView);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        SidebarItem item = items.get(position);

        if (holder instanceof SearchViewHolder) {
            bindSearch((SearchViewHolder) holder);
        } else if (holder instanceof ItemViewHolder) {
            bindItem((ItemViewHolder) holder, item, position);
        }
        // Divider needs no binding
    }

    private void bindSearch(SearchViewHolder holder) {
        holder.searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (debounceRunnable != null) {
                    debounceHandler.removeCallbacks(debounceRunnable);
                }
                debounceRunnable = () -> {
                    if (searchListener != null) {
                        searchListener.onSearch(s.toString());
                    }
                };
                debounceHandler.postDelayed(debounceRunnable, 200);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // D-pad right on search → transfer to WebView
        holder.searchInput.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (listener != null) listener.onFocusTransferToWebView();
                return true;
            }
            return false;
        });
    }

    private void bindItem(ItemViewHolder holder, SidebarItem item, int position) {
        holder.icon.setImageResource(item.iconResId);
        holder.label.setText(item.label);

        if (item.badge != null && !item.badge.isEmpty()) {
            holder.badge.setText(item.badge);
            holder.badge.setVisibility(View.VISIBLE);
        } else {
            holder.badge.setVisibility(View.GONE);
        }

        // Selected state (active group)
        holder.itemView.setSelected(item.isSelected);

        // Focus tint changes
        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                holder.icon.setColorFilter(0xFFEAEAFF); // text-primary
                holder.label.setTextColor(0xFFFFFFFF);
                if (holder.badge.getVisibility() == View.VISIBLE) {
                    holder.badge.setTextColor(0xFFA78BFA); // accent-secondary
                }
            } else {
                holder.icon.setColorFilter(0xFF606080); // text-muted
                holder.label.setTextColor(0xFFA0A0C0); // text-secondary
                if (holder.badge.getVisibility() == View.VISIBLE) {
                    holder.badge.setTextColor(0xFF606080);
                }
            }
        });

        // Click
        holder.itemView.setOnClickListener(v -> {
            if (listener != null && item.actionType != null) {
                listener.onItemClick(item);
            }
        });

        // D-pad right → transfer focus to WebView
        holder.itemView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (listener != null) listener.onFocusTransferToWebView();
                    return true;
                }
            }
            return false;
        });
    }

    // ── ViewHolders ─────────────────────────────────────────────

    static class SearchViewHolder extends RecyclerView.ViewHolder {
        final EditText searchInput;

        SearchViewHolder(View v) {
            super(v);
            searchInput = v.findViewById(R.id.sidebar_search_input);
        }
    }

    static class ItemViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView badge;

        ItemViewHolder(View v) {
            super(v);
            icon = v.findViewById(R.id.sidebar_icon);
            label = v.findViewById(R.id.sidebar_label);
            badge = v.findViewById(R.id.sidebar_badge);
        }
    }

    static class DividerViewHolder extends RecyclerView.ViewHolder {
        DividerViewHolder(View v) {
            super(v);
            v.setFocusable(false);
            v.setFocusableInTouchMode(false);
        }
    }
}
