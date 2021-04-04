package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;


import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScrollSlidingTextTabStrip;
import org.telegram.ui.Components.SeekBarView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;

public class AnimationSettingsActivity extends BaseFragment {
    private LinearLayout contentView;
    private LinearLayout tabLayout;

    int minValues [] = new int [] {0, 0, 0, 0};
    int maxValues [] = new int [] {100, 100, 500, 500};
    int defValues [] = new int [] {0, 58, 0, 500};
    int values[][];

    @Override
    public View createView(Context context) {
        fragmentView = new SizeNotifierFrameLayout(context, parentLayout);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        actionBar.setTitle("Animation Settings");
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem settings = menu.addItem(0, R.drawable.ic_ab_other);
        settings.setContentDescription(LocaleController.getString("AccDescrMoreOptions", R.string.AccDescrMoreOptions));
        settings.setTag(null);
        settings.addSubItem(0, 0, "Restore to Default");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    saveValues();
                    finishFragment();
                }
                if (id == 0) {
                    setDefValues();
                    updateTab(currentSection);
                }
            }
        });


        contentView = new LinearLayout(context);
        contentView.setOrientation(LinearLayout.VERTICAL);
        ((SizeNotifierFrameLayout) fragmentView).addView(contentView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ScrollSlidingTextTabStrip slider = createScrollingTextTabStrip(context);
        slider.addTextTab(0, "Short Text");
        slider.addTextTab(1, "Long Text");
        slider.addTextTab(2, "Link");
        slider.addTextTab(3, "Emoji");
        slider.addTextTab(4, "Sticker");
        slider.addTextTab(5, "Voice");
        slider.addTextTab(6, "Video");
        contentView.addView(slider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.TOP));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        contentView.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        tabLayout = new LinearLayout(context);
        tabLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(tabLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout durationContainer = new FrameLayout(context);
        durationContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        TextView textDuration = new TextView(context);
        textDuration.setText("Duration");
        durationContainer.addView(textDuration, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 20, 10, 10, 10));
        TextView textValueDuration = new TextView(context);
        textValueDuration.setText("500ms");
        durationContainer.addView(textValueDuration, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 10, 10, 20, 10));
        tabLayout.addView(durationContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        loadValues();
        contentView.post(new Runnable() {
            @Override
            public void run() {
                updateTab(0);
            }
        });
        return fragmentView;
    }

    ArrayList<ArrayList<ArrayList<Integer>>> settings;
    private int currentSection = 0;

    private void loadValues() {
        ArrayList<String> sections = new ArrayList<>();
        sections.add("anim_simple");
        sections.add("anim_large");
        sections.add("anim_link");
        sections.add("anim_emoji");
        sections.add("anim_sticker");
        sections.add("anim_voice");
        sections.add("anim_video");
        settings = new ArrayList<>();

        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        for (int i = 0; i < sections.size(); i++) {
            ArrayList<String> easings = new ArrayList<>();
            if (i == 0 || i == 1 || i == 2 || i == 5) {
                easings.add("_x_position");
                easings.add("_y_position");
                easings.add("_shape_position");
                easings.add("_text_position");
                easings.add("_color_position");
                easings.add("_time_position");
            }

            if (i == 3 || i == 4) {
                easings.add("_x_position");
                easings.add("_y_position");
                easings.add("_scale_position");
                easings.add("_time_position");
            }

            if (i == 6) {
                easings.add("_x_position");
                easings.add("_y_position");
                easings.add("_time_position");
            }

            ArrayList<ArrayList<Integer>> eValues = new ArrayList<>();
            for (int j = 0; j < easings.size(); j++) {
                ArrayList<Integer> values = new ArrayList<>();
                for (int v = 0; v < 4; v++) {
                    values.add(preferences.getInt(sections.get(i) + easings.get(j) + "_val_" + v, defValues[v]));
                }
                eValues.add(values);
            }
            settings.add(eValues);
        }
    }

    private void setDefValues() {
        ArrayList<String> sections = new ArrayList<>();
        sections.add("anim_simple");
        sections.add("anim_large");
        sections.add("anim_link");
        sections.add("anim_emoji");
        sections.add("anim_sticker");
        sections.add("anim_voice");
        sections.add("anim_video");
        settings = new ArrayList<>();

        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        for (int i = 0; i < sections.size(); i++) {
            ArrayList<String> easings = new ArrayList<>();
            if (i == 0 || i == 1 || i == 2 || i == 5) {
                easings.add("_x_position");
                easings.add("_y_position");
                easings.add("_shape_position");
                easings.add("_text_position");
                easings.add("_color_position");
                easings.add("_time_position");
            }

            if (i == 3 || i == 4) {
                easings.add("_x_position");
                easings.add("_y_position");
                easings.add("_scale_position");
                easings.add("_time_position");
            }

            if (i == 6) {
                easings.add("_x_position");
                easings.add("_y_position");
                easings.add("_time_position");
            }

            ArrayList<ArrayList<Integer>> eValues = new ArrayList<>();
            for (int j = 0; j < easings.size(); j++) {
                ArrayList<Integer> values = new ArrayList<>();
                for (int v = 0; v < 4; v++) {
                    values.add(defValues[v]);
                }
                eValues.add(values);
            }
            settings.add(eValues);
        }
    }

    private void saveValues() {
        ArrayList<String> sections = new ArrayList<>();
        sections.add("anim_simple");
        sections.add("anim_large");
        sections.add("anim_link");
        sections.add("anim_emoji");
        sections.add("anim_sticker");
        sections.add("anim_voice");
        sections.add("anim_video");

        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        for (int i = 0; i < sections.size(); i++) {
            ArrayList<String> easings = new ArrayList<>();
            if (i == 0 || i == 1 || i == 2 || i == 5) {
                easings.add("_x_position");
                easings.add("_y_position");
                easings.add("_shape_position");
                easings.add("_text_position");
                easings.add("_color_position");
                easings.add("_time_position");
            }

            if (i == 3 || i == 4) {
                easings.add("_x_position");
                easings.add("_y_position");
                easings.add("_scale_position");
                easings.add("_time_position");
            }

            if (i == 6) {
                easings.add("_x_position");
                easings.add("_y_position");
                easings.add("_time_position");
            }

            for (int j = 0; j < easings.size(); j++) {
                for (int v = 0; v < 4; v++) {
                    editor.putInt(sections.get(i) + easings.get(j) + "_val_" + v, settings.get(i).get(j).get(v));
                }
            }
        }
        editor.commit();
    }

    private class SeekBarDelegate implements SeekBarView.SeekBarViewDelegate {
        private TextView textView;
        private int parIndex;
        private int valIndex;

        public SeekBarDelegate(int parIndex, int valIndex, TextView textView) {
            this.parIndex = parIndex;
            this.valIndex = valIndex;
            this.textView = textView;
        }

        @Override
        public void onSeekBarDrag(boolean stop, float progress) {
            int val = (int)((maxValues[valIndex] - minValues[valIndex]) * progress);
            settings.get(currentSection).get(parIndex).set(valIndex, val);
            textView.setText(String.valueOf(val));
        }

        @Override
        public void onSeekBarPressed(boolean pressed) {

        }
    }

    private void updateTab(int tab) {
        ArrayList<String> captions = new ArrayList<>();

        if (tab == 0 || tab == 1 || tab == 2 || tab == 5) {
            captions.add("X Position");
            captions.add("Y Position");
            captions.add("Bubble shape");
            captions.add("Text scale");
            captions.add("Color change");
            captions.add("Time appears");
        }

        if (tab == 3 || tab == 4) {
            captions.add("X Position");
            captions.add("Y Position");
            captions.add("Scale");
            captions.add("Time appears");
        }

        if (tab == 6) {
            captions.add("X Position");
            captions.add("Y Position");
            captions.add("Time appears");
        }

        tabLayout.removeAllViews();

        values = new int[captions.size()][4];
        for (int i = 0; i < captions.size(); i++) {
            LinearLayout easingLayout = new LinearLayout(getParentActivity());
            easingLayout.setOrientation(LinearLayout.VERTICAL);

            TextView textView = new TextView(getParentActivity());
            textView.setGravity(Gravity.CENTER);
            textView.setText(captions.get(i));
            easingLayout.addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 48, Gravity.LEFT, 10, 0, 0, 0));

            for (int j = 0; j < 4; j++) {
                FrameLayout sliderLayout = new FrameLayout(getParentActivity());
                TextView easingValue = new TextView(getParentActivity());
                easingValue.setText(String.valueOf(settings.get(tab).get(i).get(j)));
                easingValue.setGravity(Gravity.CENTER);
                sliderLayout.addView(easingValue, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER, 0, 0, 5, 0));
                SeekBarView sizeBar = new SeekBarView(getParentActivity());
                sizeBar.setReportChanges(true);
                sizeBar.setDelegate(new SeekBarDelegate(i, j, easingValue));
                sizeBar.setProgress((float) settings.get(tab).get(i).get(j) / (maxValues[j] - minValues[j]));
                sliderLayout.addView(sizeBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.LEFT | Gravity.TOP, 5, 5, 45, 5));
                easingLayout.addView(sliderLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }

            tabLayout.addView(easingLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.NO_GRAVITY, 0, 10, 0, 0));
        }
     }

    @Override
    public boolean isSwipeBackEnabled(MotionEvent event) {
        return false;
    }

    private ScrollSlidingTextTabStrip createScrollingTextTabStrip(Context context) {
        ScrollSlidingTextTabStrip scrollSlidingTextTabStrip = new ScrollSlidingTextTabStrip(context);
        scrollSlidingTextTabStrip.setInitialTabId(0);
        scrollSlidingTextTabStrip.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        scrollSlidingTextTabStrip.setColors(Theme.key_profile_tabSelectedLine, Theme.key_profile_tabSelectedText, Theme.key_profile_tabText, Theme.key_profile_tabSelector);
        scrollSlidingTextTabStrip.setDelegate(new ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate() {
            @Override
            public void onPageSelected(int id, boolean forward) {
                currentSection = id;
                updateTab(id);
            }

            @Override
            public void onPageScrolled(float progress) {

            }
        });
        return scrollSlidingTextTabStrip;
    }
}
