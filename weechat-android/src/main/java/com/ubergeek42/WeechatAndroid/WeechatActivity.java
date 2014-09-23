/*******************************************************************************
 * Copyright 2012 Keith Johnson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.ubergeek42.WeechatAndroid;

import java.security.cert.CertPath;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.app.FragmentManager;
import android.support.v4.view.ViewPager;
import android.support.v4.view.ViewPager.OnPageChangeListener;
import android.support.v4.widget.DrawerLayout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.ActionBarSherlock;
import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.ubergeek42.WeechatAndroid.adapters.MainPagerAdapter;
import com.ubergeek42.WeechatAndroid.adapters.NickListAdapter;
import com.ubergeek42.WeechatAndroid.fragments.BufferFragment;
import com.ubergeek42.WeechatAndroid.service.Buffer;
import com.ubergeek42.WeechatAndroid.service.BufferList;
import com.ubergeek42.WeechatAndroid.service.RelayService;
import com.ubergeek42.WeechatAndroid.service.RelayServiceBinder;
import com.ubergeek42.WeechatAndroid.utils.MyMenuItemStuffListener;
import com.ubergeek42.weechat.relay.RelayConnectionHandler;

public class WeechatActivity extends SherlockFragmentActivity implements RelayConnectionHandler, OnPageChangeListener, ActionBarSherlock.OnCreateOptionsMenuListener {

    private static Logger logger = LoggerFactory.getLogger("WA");
    final private static boolean DEBUG = BuildConfig.DEBUG;
    final private static boolean DEBUG_OPTIONS_MENU = false;
    final private static boolean DEBUG_LIFECYCLE = true;
    final private static boolean DEBUG_CONNECION = true;
    final private static boolean DEBUG_INTENT = true;
    final private static boolean DEBUG_BUFFERS = false;
    final private static boolean DEBUG_DRAWER = true;

    public @Nullable RelayServiceBinder relay;
    private Menu menu;
    private ViewPager ui_pager;
    private MainPagerAdapter adapter;
    private InputMethodManager imm;
    private CutePagerTitleStrip ui_strip;
    
    private boolean slidy;
    private boolean drawer_enabled = true;
    private boolean drawer_showing = false;
    private DrawerLayout ui_drawer_layout = null;
    private View ui_drawer = null;
    private ActionBarDrawerToggle drawer_toggle = null;
    private @NonNull ImageView ui_info;

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////// life cycle
    ////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (DEBUG_LIFECYCLE) logger.debug("onCreate({})", savedInstanceState);
        super.onCreate(savedInstanceState);

        // start background service (if necessary)
        startService(new Intent(this, RelayService.class));

        // load layout
        setContentView(R.layout.main_screen);

        // prepare pager
        FragmentManager manager = getSupportFragmentManager();
        ui_pager = (ViewPager) findViewById(R.id.main_viewpager);
        adapter = new MainPagerAdapter(this, manager, ui_pager);
        ui_pager.setAdapter(adapter);

        // prepare action bar
        ActionBar ui_action_bar = getSupportActionBar();
        ui_action_bar.setHomeButtonEnabled(true);
        ui_action_bar.setDisplayShowCustomEnabled(true);
        ui_action_bar.setDisplayShowTitleEnabled(false);
        LayoutInflater inflater = (LayoutInflater) this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        ui_strip = (CutePagerTitleStrip) inflater.inflate(R.layout.cute_pager_title_strip_layout, null);
        ui_strip.setViewPager(ui_pager);
        ui_strip.setOnPageChangeListener(this);
        ui_action_bar.setCustomView(ui_strip);

        // this is the text view behind the ui_pager
        // it says stuff like 'connecting', 'disconnected' et al
        ui_info = (ImageView) findViewById(R.id.info);

        // if this is true, we've got notification drawer and have to deal with it
        // setup drawer toggle, which calls drawerVisibilityChanged()
        slidy = getResources().getBoolean(R.bool.slidy);
        if (slidy) {
            ui_drawer_layout = (DrawerLayout) findViewById(R.id.drawer_layout);
            ui_drawer = findViewById(R.id.bufferlist_fragment);
            drawer_toggle = new ActionBarDrawerToggle(this, ui_drawer_layout, R.drawable.ic_drawer,
                    R.string.open_drawer, R.string.close_drawer) {

                @SuppressWarnings("SimplifiableConditionalExpression")
                @Override
                public void onDrawerStateChanged(int newState) {
                    super.onDrawerStateChanged(newState);
                    boolean showing = (newState == DrawerLayout.STATE_IDLE) ?
                            ui_drawer_layout.isDrawerVisible(ui_drawer) : true;
                    if (drawer_showing != showing) {
                        drawer_showing = showing;
                        drawerVisibilityChanged();
                    }

                }
            };
            ui_drawer_layout.setDrawerListener(drawer_toggle);
            ui_action_bar.setDisplayHomeAsUpEnabled(true);
        }

        // TODO Read preferences from background, its IO, 31ms strict mode!
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        setTitle("WeechatAndroid v" + BuildConfig.VERSION_NAME);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////

    /** bind to relay service, which results in:
     **   onServiceConnected, which
     **     adds relay connection handler */
    // TODO: android.app.ServiceConnectionLeaked: Activity com.ubergeek42.WeechatAndroid.WeechatActivity
    // TODO: has leaked ServiceConnection com.ubergeek42.WeechatAndroid.WeechatActivity$1@424fdbe8 that was originally bound here
    // TODO: apparently onStop() sometimes doesn't get to unbind the service as onServiceConnected is called too late
    // TODO: then onStart() is trying to bind again and boom! anyways, this doesn't do any visible harm...
    @Override
    protected void onStart() {
        if (DEBUG_LIFECYCLE) logger.debug("onStart()");
        super.onStart();
        bindService(new Intent(this, RelayService.class), service_connection, Context.BIND_AUTO_CREATE);
    }

    /** remove relay connection handler and
     ** unbind from service */
    @Override
    protected void onStop() {
        if (DEBUG_LIFECYCLE) logger.debug("onStop()");
        super.onStop();

        if (relay != null) {
            relay.removeRelayConnectionHandler(WeechatActivity.this);
            unbindService(service_connection);
            relay = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (DEBUG_LIFECYCLE) logger.debug("onDestroy()");
        super.onDestroy();
    }

    //////////////////////////////////////////////////////////////////////////////////////////////// these two are necessary for the drawer

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (slidy) drawer_toggle.syncState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (slidy) drawer_toggle.onConfigurationChanged(newConfig);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////// relay connection
    ////////////////////////////////////////////////////////////////////////////////////////////////

    ServiceConnection service_connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG_LIFECYCLE) logger.debug("onServiceConnected(), main thread? {}", Looper.myLooper() == Looper.getMainLooper());
            relay = (RelayServiceBinder) service;
            relay.addRelayConnectionHandler(WeechatActivity.this);

            // Check if the service is already connected to the weechat relay, and if so load it up
            if (relay.isConnection(RelayService.BUFFERS_LISTED))
                WeechatActivity.this.onBuffersListed();
            if (relay.isConnection(RelayService.CONNECTED))
                WeechatActivity.this.onConnect();
            else if (relay.isConnection(RelayService.DISCONNECTED))
                WeechatActivity.this.onDisconnect();
            else if (relay.isConnection(RelayService.CONNECTING))
                WeechatActivity.this.onConnecting();

            // open buffer that MIGHT be open in the service
            // update hot count
            for (String full_name : BufferList.synced_buffers_full_names)
                openBufferButDontHideBufferList(full_name, false, false);
            updateHotCount(BufferList.hot_count);

            if (slidy) maybeShowDrawer();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG_LIFECYCLE) logger.debug("onServiceDisconnected()");
            relay = null;
        }
    };

    //////////////////////////////////////////////////////////////////////////////////////////////// RelayConnectionHandler

    @Override public void onConnecting() {
        if (DEBUG_CONNECION) logger.debug("onConnecting()");
        setInfoImage(R.drawable.ic_big_connecting);
        if (slidy) disableDrawer();
    }

    /** creates and updates the hotlist
     ** makes sure we update action bar menu after a connection change */
    @Override public void onConnect() {
        if (DEBUG_CONNECION) logger.debug("onConnect()");
        setInfoImage(R.drawable.ic_big_connected);
        makeMenuReflectConnectionStatus();
    }

    @Override public void onAuthenticated() {}

    @Override public void onBuffersListed() {
        if (DEBUG_CONNECION) logger.debug("onBuffersListed()");
        if (slidy) {
            enableDrawer();
            maybeShowDrawer();
        }
    }

    /** makes sure we update action bar menu after a connection change */
    @Override public void onDisconnect() {
        if (DEBUG_CONNECION) logger.debug("onDisconnect()");
        makeMenuReflectConnectionStatus();
        setInfoImage(R.drawable.ic_big_disconnected);
        if (slidy) disableDrawer();

    }

    @Override public void onError(final String errorMsg, Object extraData) {
        if (DEBUG_CONNECION) logger.debug("onError({}, ...)", errorMsg);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(getBaseContext(), "Error: " + errorMsg, Toast.LENGTH_LONG).show();
            }
        });        
        if (extraData instanceof SSLException && relay != null) {
            if (DEBUG) logger.error("...cause: {}", ((Throwable) extraData).getCause());
            SSLException e1 = (SSLException) extraData;
            if (e1.getCause() instanceof CertificateException) {
                CertificateException e2 = (CertificateException) e1.getCause();
                
                if (e2.getCause() instanceof CertPathValidatorException) {
                    CertPathValidatorException e = (CertPathValidatorException) e2.getCause();
                    CertPath cp = e.getCertPath();                    
                    
                    // Set the cert error on the backend
                    relay.setCertificateError((X509Certificate) cp.getCertificates().get(0));
                    
                    // Start an activity to attempt establishing trust
                    Intent i = new Intent(this, SSLCertActivity.class);
                    startActivity(i);
                }
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////// OnPageChangeListener
    ////////////////////////////////////////////////////////////////////////////////////////////////

    @Override public void onPageScrollStateChanged(int state) {}
    @Override public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {}

    @Override public void onPageSelected(int position) {
        updateMenuItems();
        hideSoftwareKeyboard();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////// MENU
    ////////////////////////////////////////////////////////////////////////////////////////////////

    volatile private int hot_number = 0;
    private TextView ui_hot = null;

    /** update hot count (that red square over the bell icon) at any time
     ** also sets "hot_number" in case menu has to be recreated
     ** can be called off the main thread */
    public void updateHotCount(final int new_hot_number) {
        if (DEBUG_OPTIONS_MENU) logger.debug("updateHotCount(), hot: {} -> {}", hot_number, new_hot_number);
        hot_number = new_hot_number;
        if (ui_hot == null) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (new_hot_number == 0)
                    ui_hot.setVisibility(View.INVISIBLE);
                else {
                    ui_hot.setVisibility(View.VISIBLE);
                    ui_hot.setText(Integer.toString(new_hot_number));
                }
            }
        });
    }

    /** hide or show nicklist/close menu item according to buffer
     ** MUST be called on main thread */
    private void updateMenuItems() {
        if (menu == null) return;
        boolean buffer_visible = adapter.getCount() > 0;
        menu.findItem(R.id.menu_nicklist).setVisible(buffer_visible);
        menu.findItem(R.id.menu_close).setVisible(buffer_visible);
    }

    /** Can safely hold on to this according to docs
     ** http://developer.android.com/reference/android/app/Activity.html#onCreateOptionsMenu(android.view.Menu) **/
    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        if (DEBUG_OPTIONS_MENU) logger.debug("onCreateOptionsMenu(...)");
        MenuInflater menuInflater = getSupportMenuInflater();
        menuInflater.inflate(R.menu.menu_actionbar, menu);
        final View menu_hotlist = menu.findItem(R.id.menu_hotlist).getActionView();
        ui_hot = (TextView) menu_hotlist.findViewById(R.id.hotlist_hot);
        updateHotCount(hot_number);
        new MyMenuItemStuffListener(menu_hotlist, "Show hot message") {
            @Override
            public void onClick(View v) {
                onHotlistSelected();
            }
        };
        this.menu = menu;
        makeMenuReflectConnectionStatus();
        return super.onCreateOptionsMenu(menu);
    }

    /** handle the options when the user presses the menu button */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (DEBUG_OPTIONS_MENU) logger.debug("onOptionsItemSelected({})", item);
        switch (item.getItemId()) {
            case android.R.id.home: {
                if (slidy && drawer_enabled) {
                    if (drawer_showing) hideDrawer();
                    else showDrawer();
                }
                break;
            }
            case R.id.menu_connection_state: {
                if (relay != null) {
                    if (relay.isConnection(RelayService.CONNECTED)) relay.disconnect();
                    else relay.connect();
                }
                break;
            }
            case R.id.menu_preferences: {
                Intent i = new Intent(this, WeechatPreferencesActivity.class);
                startActivity(i);
                break;
            }
            case R.id.menu_close: {
                BufferFragment current = adapter.getCurrentBufferFragment();
                if (current != null)
                    current.onBufferClosed();
                break;
            }
            case R.id.menu_about: {
                Intent i = new Intent(this, WeechatAboutActivity.class);
                startActivity(i);
                break;
            }
            case R.id.menu_quit: {
                if (relay != null) {
                    relay.disconnect();
                }
                unbindService(service_connection);
                relay = null;
                stopService(new Intent(this, RelayService.class));
                finish();
                break;
            }
            case R.id.menu_hotlist:
                break;
            case R.id.menu_nicklist:
                if (relay == null) break;
                Buffer buffer = relay.getBufferByFullName(adapter.getFullNameAt(ui_pager.getCurrentItem()));
                if (buffer == null) break;

                NickListAdapter nicklistAdapter = new NickListAdapter(WeechatActivity.this, buffer);

                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setAdapter(nicklistAdapter, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int position) {
                        // TODO define something to happen here
                    }
                });
                AlertDialog dialog = builder.create();
                dialog.setTitle("squirrels are awesome");
                dialog.setOnShowListener(nicklistAdapter);
                dialog.setOnDismissListener(nicklistAdapter);
                dialog.show();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void onHotlistSelected() {
        if (DEBUG_OPTIONS_MENU) logger.debug("onHotlistSelected()");
        if (relay == null) return;

        for (Buffer buffer : BufferList.getBufferList()) {
            if ((buffer.type == Buffer.PRIVATE && buffer.unreads > 0) ||
                    buffer.highlights > 0) {
                openBuffer(buffer.full_name, true, true);
                return;
            }
        }
        Toast.makeText(this, "There are no hot buffers for now", Toast.LENGTH_SHORT).show();
    }

    /** change first menu item from connect to disconnect or back depending on connection status */
    private void makeMenuReflectConnectionStatus() {
        if (DEBUG_OPTIONS_MENU) logger.debug("makeMenuReflectConnectionStatus()");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (WeechatActivity.this.menu != null) {
                    MenuItem connectionStatus = WeechatActivity.this.menu.findItem(R.id.menu_connection_state);
                    if (relay != null && (relay.isConnection(RelayService.CONNECTED)))
                        connectionStatus.setTitle(R.string.disconnect);
                    else
                        connectionStatus.setTitle(R.string.connect);
                }
            }
        });
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////// MISC
    ////////////////////////////////////////////////////////////////////////////////////////////////

    public void openBufferButDontHideBufferList(String full_name, boolean focus, boolean scroll) {
        if (DEBUG_BUFFERS) logger.debug("openBufferButDontHideBufferList({})", full_name);
        adapter.openBuffer(full_name, focus, scroll);
    }

    public void openBuffer(String full_name, boolean focus, boolean scroll) {
        if (DEBUG_BUFFERS) logger.debug("openBuffer({})", full_name);
        if (relay != null && relay.isConnection(RelayService.CONNECTED))
            adapter.openBuffer(full_name, focus, scroll);
        else
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show();
        if (slidy) hideDrawer();
    }

    // In own thread to prevent things from breaking
    public void closeBuffer(String full_name) {
        if (DEBUG_BUFFERS) logger.debug("closeBuffer({})", full_name);
        adapter.closeBuffer(full_name);
        if (slidy) maybeShowDrawer();
    }

    /** hides the software keyboard, if any */
    public void hideSoftwareKeyboard() {
        imm.hideSoftInputFromWindow(ui_pager.getWindowToken(), 0);
    }

    @Override
    public void onBackPressed() {
        if (DEBUG_LIFECYCLE) logger.debug("onBackPressed()");
        if (slidy && drawer_showing) hideDrawer();
        else super.onBackPressed();
    }

    /** called if the text of one of the buffers has been changed
     ** and the ui_strip doesn't update itself because there's no scrolling */
    public void updateCutePagerTitleStrip() {
        ui_strip.updateText();
    }



    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////// drawer stuff
    ////////////////////////////////////////////////////////////////////////////////////////////////

    public void drawerVisibilityChanged() {
        if (DEBUG_DRAWER || true) logger.debug("drawerVisibilityChanged()");
        BufferFragment current = adapter.getCurrentBufferFragment();
        if (current != null)
            current.maybeChangeVisibilityState();
    }

    public boolean isPagerNoticeablyObscured() {
        return drawer_showing;
        //todo
    }

    public void enableDrawer() {
        if (DEBUG_DRAWER) logger.debug("enableDrawer()");
        drawer_enabled = true;
        ui_pager.post(new Runnable() {
            @Override public void run() {
                ui_drawer_layout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
            }
        });
    }

    public void disableDrawer() {
        if (DEBUG_DRAWER) logger.debug("disableDrawer()");
        drawer_enabled = false;
        ui_pager.post(new Runnable() {
            @Override public void run() {
                ui_drawer_layout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
            }
        });
    }

    public void showDrawer() {
        if (DEBUG_DRAWER) logger.debug("showDrawer()");
        ui_pager.post(new Runnable() {
            @Override public void run() {
                ui_drawer_layout.openDrawer(ui_drawer);
            }
        });
    }

    public void hideDrawer() {
        if (DEBUG_DRAWER) logger.debug("hideDrawer()");
        ui_pager.post(new Runnable() {
            @Override public void run() {
                ui_drawer_layout.closeDrawer(ui_drawer);
            }
        });
    }

    /** pop up drawer if connected & no pages in the adapter **/
    public void maybeShowDrawer() {
        if (DEBUG_DRAWER) logger.debug("maybeShowDrawer()");
        if (!drawer_showing)
            ui_pager.post(new Runnable() {
                @Override public void run() {
                    if (relay != null && relay.isConnection(RelayService.BUFFERS_LISTED) && adapter.getCount() == 0)
                        showDrawer();
                }
            });
    }

    /** set image that appears in the pager when no pages are open */
    private void setInfoImage(final int id) {
        ui_info.post(new Runnable() {
            @Override public void run() {
                ui_info.setImageResource(id);
            }
        });
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////// intent
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /** we may get intent while we are connected to the service and when we are not.
     ** empty (but present) full_name means open the drawer (in case we have highlights
     ** on multiple buffers */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        String full_name = getIntent().getStringExtra("full_name");
        if (DEBUG_INTENT) logger.debug("onNewIntent(...), full_name='{}'", intent);

        if (full_name != null) {
            if ("".equals(full_name)) maybeShowDrawer();
            else openBuffer(full_name, true, true);
        }
    }
}
