package com.liskovsoft.smartyoutubetv2.tv.ui.browse;

import android.os.Bundle;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity;

public class BrowseActivity extends LeanbackActivity {
    private static final String TAG = BrowseActivity.class.getSimpleName();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.fragment_main);
        } catch (NoClassDefFoundError e) {
            // Failed resolution of: Landroidx/lifecycle/ViewTreeLifecycleOwner;
            MessageHelpers.showMessage(this, e.getMessage());
        }
    }

    @Override
    public void onBackPressed() {
        // RavTube overview: Back from the content first returns to the section tiles
        androidx.fragment.app.Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.main_frame);
        if (fragment instanceof com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav.RavBrowseFragment
                && ((com.liskovsoft.smartyoutubetv2.tv.ui.browse.rav.RavBrowseFragment) fragment).handleBack()) {
            return;
        }

        super.onBackPressed();
    }

    @Override
    protected void initTheme() {
        int browseThemeResId = MainUIData.instance(this).getColorScheme().browseThemeResId;
        if (browseThemeResId > 0) {
            setTheme(browseThemeResId);
        }
    }
}
