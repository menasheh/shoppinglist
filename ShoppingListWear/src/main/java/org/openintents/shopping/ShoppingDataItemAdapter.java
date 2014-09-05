package org.openintents.shopping;

import android.content.Context;
import android.support.wearable.view.WearableListView;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMapItem;

import org.openintents.shopping.library.provider.ShoppingContract;

import java.util.ArrayList;
import java.util.List;


public class ShoppingDataItemAdapter extends WearableListView.Adapter {

    private static final String EMPTY_STRING = "";
    private List<DataItem> mItems = new ArrayList<DataItem>();
    private Context mContext;

    public ShoppingDataItemAdapter(Context context){
        mContext = context;
    }

    @Override
    public WearableListView.ViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {
        return new WearableListView.ViewHolder(new ShoppingItemView(mContext, 14, 20));
    }

    @Override
    public void onBindViewHolder(WearableListView.ViewHolder viewHolder, int i) {
        String name = DataMapItem.fromDataItem(mItems.get(i)).getDataMap().getString(ShoppingContract.ContainsFull.ITEM_NAME);
        String quantity =DataMapItem.fromDataItem(mItems.get(i)).getDataMap().getString(ShoppingContract.ContainsFull.QUANTITY);
        String units = DataMapItem.fromDataItem(mItems.get(i)).getDataMap().getString(ShoppingContract.ContainsFull.ITEM_UNITS);
        String titleDisplay;
        if (quantity == null){
            titleDisplay = name;
        } else {
            if (units == null){
                titleDisplay = quantity + " " + name;
            } else {
                titleDisplay = quantity+ units + " " + name;
            }
        }
        ((TextView)viewHolder.itemView.findViewById(R.id.title)).setText(titleDisplay);

        String tags = DataMapItem.fromDataItem(mItems.get(i)).getDataMap().getString(ShoppingContract.ContainsFull.ITEM_TAGS);
        if (tags == null){
            tags = EMPTY_STRING;
        }
        ((TextView)viewHolder.itemView.findViewById(R.id.tags)).setText(tags);
    }

    public void setItems(DataItemBuffer items) {
        for (int i= 0; i < items.getCount();i++){
            mItems.add(items.get(i));
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        if (mItems == null){
            return 0;
        } else {
            return mItems.size();
        }
    }

    public void remove(int position) {
        mItems.remove(position);
        notifyDataSetChanged();
    }
}