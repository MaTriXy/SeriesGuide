
package com.battlelancer.seriesguide.adapters;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.Lists;
import com.battlelancer.seriesguide.ui.ListsFragment;
import com.battlelancer.seriesguide.util.ListsTools;

/**
 * Returns {@link ListsFragment}s for every list in the database, makes sure there is always at
 * least one.
 */
public class ListsPagerAdapter extends MultiPagerAdapter {

    private Context mContext;

    private Cursor mLists;

    public ListsPagerAdapter(FragmentManager fm, Context context) {
        super(fm);
        mContext = context.getApplicationContext();

        // load lists, order by order number, then name
        mLists = mContext.getContentResolver()
                .query(Lists.CONTENT_URI, ListsQuery.PROJECTION, null, null,
                        Lists.SORT_ORDER_THEN_NAME);

        // precreate first list
        if (mLists != null && mLists.getCount() == 0) {
            String listName = mContext.getString(R.string.first_list);
            ListsTools.addList(context, listName);
        }
    }

    @Override
    public Fragment getItem(int position) {
        if (mLists == null) {
            return null;
        }

        mLists.moveToPosition(position);
        return ListsFragment.newInstance(mLists.getString(0));
    }

    @Override
    public int getCount() {
        if (mLists == null) {
            return 1;
        }
        return mLists.getCount();
    }

    @Override
    public CharSequence getPageTitle(int position) {
        if (mLists == null) {
            return "";
        }

        mLists.moveToPosition(position);
        return mLists.getString(1);
    }

    @Override
    public int getItemPosition(Object object) {
        return POSITION_NONE;
    }

    public String getListId(int position) {
        if (mLists == null) {
            return null;
        }

        mLists.moveToPosition(position);
        return mLists.getString(0);
    }

    public void onListsChanged() {
        if (mLists != null && !mLists.isClosed()) {
            Cursor newCursor = mContext.getContentResolver()
                    .query(Lists.CONTENT_URI, ListsQuery.PROJECTION, null, null,
                            Lists.SORT_ORDER_THEN_NAME);

            Cursor oldCursor = mLists;
            mLists = newCursor;
            oldCursor.close();

            notifyDataSetChanged();
        }
    }

    /**
     * Close the {@link Cursor} backing this {@link ListsPagerAdapter}.
     */
    public void onCleanUp() {
        if (mLists != null && !mLists.isClosed()) {
            mLists.close();
            mLists = null;
        }
    }

    interface ListsQuery {
        String[] PROJECTION = new String[] {
                Lists.LIST_ID,
                Lists.NAME
        };

        int ID = 0;
        int NAME = 1;
    }
}
