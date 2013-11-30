package org.openintents.shopping.glass;

import android.accounts.AccountManager;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.common.AccountPicker;
import com.google.api.client.util.IOUtils;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final boolean debug = true;

    private static final String PARAM_AUTH_TOKEN =
            "com.example.mirror.android.AUTH_TOKEN";

    public static final String PREF_OAUTH_TOKEN = "OAUTH_TOKEN";
    public static final String PREF_LAST_MIRROR_ID = "LAST_MIRROR_ID";

    private static final int REQUEST_ACCOUNT_PICKER = 1;
    private static final int REQUEST_AUTHORIZATION = 2;

    private static final String GLASS_TIMELINE_SCOPE =
            "https://www.googleapis.com/auth/glass.timeline";
    private static final String GLASS_LOCATION_SCOPE =
            "https://www.googleapis.com/auth/glass.location";
    private static final String SCOPE = String.format("oauth2: %s %s",
            GLASS_TIMELINE_SCOPE, GLASS_LOCATION_SCOPE);

    private static ExecutorService sThreadPool =
            Executors.newSingleThreadExecutor();

    private final Handler mHandler = new Handler();

    private String mAuthToken;
    private Button mStartAuthButton;
    private Button mExpireTokenButton;
    private ImageButton mNewCardButton;
    private EditText mNewCardEditText;
    private boolean mInvalideShoppingVersion;
    private String mLastMirrorId;
    private OIShoppingListSender sender;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            PackageInfo info = getPackageManager().getPackageInfo("org.openintents.shopping", 0);
            if (info.versionCode < 10024) {
                mInvalideShoppingVersion = true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            mInvalideShoppingVersion = true;
        }
        if (debug) { Log.d(TAG, "mInvalideShoppingVersion="+mInvalideShoppingVersion); }


        // Define our layout
        setContentView(R.layout.activity_main);

        // Get our views
        mStartAuthButton = (Button) findViewById(R.id.oauth_button);
        mExpireTokenButton = (Button) findViewById(R.id.oauth_expire_button);
        mNewCardButton = (ImageButton) findViewById(R.id.new_card_button);
        mNewCardEditText = (EditText) findViewById(R.id.new_card_message);

        // Restore any saved instance state
        if (savedInstanceState != null) {
            onTokenResult(savedInstanceState.getString(PARAM_AUTH_TOKEN));
        } else {
            mStartAuthButton.setEnabled(true);
            mExpireTokenButton.setEnabled(false);
        }

        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        String oauthToken = prefs.getString(PREF_OAUTH_TOKEN, null);
        if (oauthToken != null)
        {
            if (debug) Log.d(TAG, "got OAUTH_TOKEN="+oauthToken);
            mAuthToken = oauthToken;
            mExpireTokenButton.setEnabled(true);
            mStartAuthButton.setEnabled(false);
        } else {
            if (debug) Log.d(TAG, "no save OAUTH_TOKEN");
        }

        String lastMirrorId = prefs.getString(PREF_LAST_MIRROR_ID, null);
        if (lastMirrorId != null)
        {
            if (debug) Log.d(TAG, "got LAST_MIRROR_ID="+lastMirrorId);
            mLastMirrorId=lastMirrorId;
        } else {
            mLastMirrorId=null;
        }

        mStartAuthButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // Present the user with an account picker dialog with a list
                // of their Google accounts
                Intent intent = AccountPicker.newChooseAccountIntent(
                        null, null, new String[]{GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE},
                        false, null, null, null, null);
                startActivityForResult(intent, REQUEST_ACCOUNT_PICKER);
            }
        });

        mExpireTokenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!TextUtils.isEmpty(mAuthToken)) {
                    // Expire the token, if any
                    GoogleAuthUtil.invalidateToken(MainActivity.this, mAuthToken);
                    mAuthToken = null;
                    mExpireTokenButton.setEnabled(false);
                    mStartAuthButton.setEnabled(true);
                }
            }
        });

        mNewCardButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (debug) { Log.d(TAG, "mInvalideShoppingVersion="+mInvalideShoppingVersion); }
                createNewTimelineItem(mNewCardEditText.getText().toString());
            }
        });
        sender=new OIShoppingListSender();
        sender.initSender(getApplicationContext());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(PARAM_AUTH_TOKEN, mAuthToken);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode,
            Intent data) {
        switch (requestCode) {
            case REQUEST_ACCOUNT_PICKER:
                if (RESULT_OK == resultCode) {
                    String account = data.getStringExtra(
                            AccountManager.KEY_ACCOUNT_NAME);
                    String type = data.getStringExtra(
                            AccountManager.KEY_ACCOUNT_TYPE);

                    // TODO: Cache the chosen account
                    Log.i(TAG, String.format("User selected account %s of type %s",
                            account, type));
                    fetchTokenForAccount(account);
                }
                break;
            case REQUEST_AUTHORIZATION:
                if (RESULT_OK == resultCode) {
                    String token = data.getStringExtra(
                            AccountManager.KEY_AUTHTOKEN);

                    Log.i(TAG, String.format(
                            "Authorization request returned token %s", token));
                    onTokenResult(token);
                }
                break;
        }
    }

    private JSONObject buildShoppingCard() throws JSONException {
        JSONObject card=new JSONObject();
        String html="<article><section>";
        html+="<ul class=\"text-x-small\">";
        String text="";
        String[] items=sender.getItems();
        for (String item : items) {
            text+=item+" ";
            html+="<li>"+item+"</li>\n";
        }
        html+="</ul></section><footer>\n<p>OI Shopping List</p>\n</footer></article>";
        card.put("html", html);
        card.put("text", text);
        return card;
    }
    private void createNewTimelineItem(String message) {
        if (!TextUtils.isEmpty(mAuthToken)) {
                try {
                    JSONObject notification = new JSONObject();
                    notification.put("level", "DEFAULT"); // Play a chime

                    JSONObject json = buildShoppingCard();
                    json.put("notification", notification);

                    MirrorApiClient client = MirrorApiClient.getInstance(this);
                    client.createTimelineItem(mAuthToken, json, new MirrorApiClient.Callback() {
                        @Override
                        public void onSuccess(HttpResponse response) {
                            try {
                                /*
                                BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), "UTF-8"));
                                StringBuilder builder = new StringBuilder();
                                for (String line = null; (line = reader.readLine()) != null;) {
                                    builder.append(line).append("\n");
                                }
                                JSONTokener tokener = new JSONTokener(builder.toString());
                                JSONArray finalResult = new JSONArray(tokener);
                                Log.d(TAG, "finalResult="+finalResult);
                                */
                                InputStream inputStream = response.getEntity().getContent();
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                IOUtils.copy(inputStream, baos);
                                JSONObject jsonObject = new JSONObject(baos.toString());
//                                if (debug) Log.d(TAG, "jsonObject="+jsonObject);
//                                Log.v(TAG, "onSuccess: " + EntityUtils.toString(response.getEntity()));
                                String id=jsonObject.getString("id");
                                if (debug) Log.d(TAG, "id="+id);

                                if (id!=null && (id.length()>0)) {
                                    SharedPreferences.Editor editor = getPreferences(MODE_PRIVATE).edit();
                                    editor.putString(PREF_LAST_MIRROR_ID, id);
                                    editor.commit();
                                    if (debug) Log.d(TAG, "saved LAST_MIRROR_ID="+id);
                                    mLastMirrorId=id;
                                }
                            } catch (IOException e1) {
                                // Pass
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                            Toast.makeText(MainActivity.this, "Created new timeline item",
                                    Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onFailure(HttpResponse response, Throwable e) {
                            try {
                                Log.v(TAG, "onFailure: " + EntityUtils.toString(response.getEntity()));
                            } catch (IOException e1) {
                                // Pass
                            }
                            Toast.makeText(MainActivity.this, "Failed to create new timeline item",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (JSONException e) {
                    Toast.makeText(this, "Sorry, can't serialize that to JSON",
                            Toast.LENGTH_SHORT).show();
                }
        } else {
            Toast.makeText(this, "Sorry, can't create a new timeline card without a token",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void onTokenResult(String token) {
        Log.d(TAG, "onTokenResult: " + token);
        if (!TextUtils.isEmpty(token)) {
            mAuthToken = token;
            mExpireTokenButton.setEnabled(true);
            mStartAuthButton.setEnabled(false);
            Toast.makeText(this, "New token result", Toast.LENGTH_SHORT).show();

            SharedPreferences.Editor editor = getPreferences(MODE_PRIVATE).edit();
            editor.putString(PREF_OAUTH_TOKEN, token);
            editor.commit();
            if (debug) Log.d(TAG, "saved OAUTH_TOKEN="+token);
        } else {
            mExpireTokenButton.setEnabled(false);
            mStartAuthButton.setEnabled(true);
            Toast.makeText(this, "Sorry, invalid token result", Toast.LENGTH_SHORT).show();
        }
    }

    private void fetchTokenForAccount(final String account) {
        // We fetch the token on a background thread otherwise Google Play
        // Services will throw an IllegalStateException
        sThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // If this returns immediately the OAuth framework thinks
                    // the token should be usable
                    final String token = GoogleAuthUtil.getToken(
                            MainActivity.this, account, SCOPE);

                    if (token != null) {
                        // Pass the token back to the UI thread
                        Log.i(TAG, String.format("getToken returned token %s", token));
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                onTokenResult(token);
                            }
                        });
                    }
                } catch (final UserRecoverableAuthException e) {
                    // This means that the app hasn't been authorized by the user for access
                    // to the scope, so we're going to have to fire off the (provided) Intent
                    // to arrange for that. But we only want to do this once. Multiple
                    // attempts probably mean the user said no.
                    Log.i(TAG, "Handling a UserRecoverableAuthException");

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            startActivityForResult(e.getIntent(), REQUEST_AUTHORIZATION);
                        }
                    });
                } catch (IOException e) {
                    // Something is stressed out; the auth servers are by definition
                    // high-traffic and you can't count on 100% success. But it would be
                    // bad to retry instantly, so back off
                    Log.e(TAG, "Failed to fetch auth token!", e);

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this,
                                    "Failed to fetch token, try again later", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (GoogleAuthException e) {
                    // Can't recover from this!
                    Log.e(TAG, "Failed to fetch auth token!", e);

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this,
                                    "Failed to fetch token, can't recover", Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }
}