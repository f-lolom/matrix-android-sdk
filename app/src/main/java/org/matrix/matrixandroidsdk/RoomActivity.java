package org.matrix.matrixandroidsdk;

import android.app.AlertDialog;
import android.content.Intent;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.matrix.androidsdk.RestClient;
import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.listeners.MXEventListener;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.matrixandroidsdk.fragments.MatrixMessageListFragment;
import org.matrix.matrixandroidsdk.fragments.RoomMembersDialogFragment;


/**
 * Displays a single room with messages.
 */
public class RoomActivity extends ActionBarActivity implements MatrixMessageListFragment.MatrixMessageListListener {

    public static final String EXTRA_ROOM_ID = "org.matrix.matrixandroidsdk.RoomActivity.EXTRA_ROOM_ID";

    private static final String TAG_FRAGMENT_MATRIX_MESSAGE_LIST = "org.matrix.androidsdk.RoomActivity.TAG_FRAGMENT_MATRIX_MESSAGE_LIST";
    private static final String TAG_FRAGMENT_MEMBERS_DIALOG = "org.matrix.androidsdk.RoomActivity.TAG_FRAGMENT_MEMBERS_DIALOG";
    private static final String LOG_TAG = "RoomActivity";

    private MatrixMessageListFragment mMatrixMessageListFragment;
    private MXSession mSession;
    private String mRoomId;

    private MXEventListener mSessionListener = new MXEventListener() {
        @Override
        public void onRoomStateUpdated(Room room, final Event event, Object oldVal, final Object newVal) {
            if (!mRoomId.equals(room.getRoomId())) {
                return;
            }
            RoomActivity.this.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    if (Event.EVENT_TYPE_STATE_ROOM_NAME.equals(event.type)) {
                        Log.e(LOG_TAG, "Updating room name.");
                        setTitle((String)newVal);
                    }
                    else if (Event.EVENT_TYPE_STATE_ROOM_TOPIC.equals(event.type)) {
                        Log.e(LOG_TAG, "Updating room topic.");
                        setTopic((String)newVal);
                    }
                    if (Event.EVENT_TYPE_STATE_ROOM_ALIASES.equals(event.type)) {
                        Log.e(LOG_TAG, "Updating room name (via alias).");
                        Room room = mSession.getDataHandler().getStore().getRoom(mRoomId);
                        setTitle(room.getName());
                    }
                }
            });

        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room);

        Intent intent = getIntent();
        if (!intent.hasExtra(EXTRA_ROOM_ID)) {
            Log.e(LOG_TAG, "No room ID extra.");
            finish();
            return;
        }
        mRoomId = intent.getStringExtra(EXTRA_ROOM_ID);
        Log.i(LOG_TAG, "Displaying "+mRoomId);

        findViewById(R.id.button_send).setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
                EditText editText = (EditText)findViewById(R.id.editText_messageBox);
                String body = editText.getText().toString();
                sendMessage(body);
                editText.setText("");
            }
        });


        // make sure we're logged in.
        mSession = Matrix.getInstance(getApplicationContext()).getDefaultSession();
        if (mSession == null) {
            Log.e(LOG_TAG, "No MXSession.");
            finish();
            return;
        }

        FragmentManager fm = getSupportFragmentManager();
        mMatrixMessageListFragment = (MatrixMessageListFragment) fm.findFragmentByTag(TAG_FRAGMENT_MATRIX_MESSAGE_LIST);

        if (mMatrixMessageListFragment == null) {
            // this fragment displays messages and handles all message logic
            mMatrixMessageListFragment = MatrixMessageListFragment.newInstance(mRoomId);
            fm.beginTransaction().add(R.id.anchor_fragment_messages, mMatrixMessageListFragment, TAG_FRAGMENT_MATRIX_MESSAGE_LIST).commit();
        }

        // set general room information
        Room room = mSession.getDataHandler().getStore().getRoom(mRoomId);
        setTitle(room.getName());

        setTopic(room.getTopic());


        // listen for room name or topic changes
        mSession.getDataHandler().addListener(mSessionListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mSession.getDataHandler().removeListener(mSessionListener);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.room, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        if (CommonActivityUtils.handleMenuItemSelected(this, id)) {
            return true;
        }


        if (id == R.id.action_load_more) {
            mMatrixMessageListFragment.requestPagination();
        }
        else if (id == R.id.action_invite) {
            final MXSession session = Matrix.getInstance(getApplicationContext()).getDefaultSession();
            if (session != null) {
                AlertDialog alert = CommonActivityUtils.createEditTextAlert(this, "Invite User", "@localpart:domain", new CommonActivityUtils.OnSubmitListener() {
                    @Override
                    public void onSubmit(final String text) {
                        if (TextUtils.isEmpty(text)) {
                            return;
                        }
                        if (!text.startsWith("@") || !text.contains(":")) {
                            Toast.makeText(getApplicationContext(), "User must be of the form '@name:example.com'.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        session.getRoomsApiClient().inviteToRoom(mRoomId, text.trim() , new RestClient.SimpleApiCallback<Void>() {
                            @Override
                            public void onSuccess(Void info) {
                                Toast.makeText(getApplicationContext(), "Sent invite to " + text.trim() + ".", Toast.LENGTH_LONG).show();
                            }
                        });
                    }

                    @Override
                    public void onCancelled() {

                    }
                });
                alert.show();
            }
        }
        else if (id == R.id.action_leave) {
            MXSession session = Matrix.getInstance(getApplicationContext()).getDefaultSession();
            if (session != null) {
                session.getRoomsApiClient().leaveRoom(mRoomId, new RestClient.SimpleApiCallback<Void>() {

                    @Override
                    public void onSuccess(Void info) {
                        RoomActivity.this.finish();
                    }
                });
            }
        }
        else if (id == R.id.action_members) {
            FragmentManager fm = getSupportFragmentManager();

            RoomMembersDialogFragment fragment = (RoomMembersDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_MEMBERS_DIALOG);
            if (fragment != null) {
                fragment.dismissAllowingStateLoss();
            }
            fragment = RoomMembersDialogFragment.newInstance(mRoomId);
            fragment.show(fm, TAG_FRAGMENT_MEMBERS_DIALOG);
        }
        return super.onOptionsItemSelected(item);
    }

    private void setTopic(String topic) {
        TextView topicView = ((TextView)findViewById(R.id.textView_roomTopic));
        topicView.setText(topic);
        topicView.setSelected(true); // make the marquee scroll
    }

    private void sendMessage(String body) {
        if (!TextUtils.isEmpty(body)) {
            if (body.length() > 4 && (body.toLowerCase().startsWith("/me ") || body.toLowerCase().startsWith("/em "))) {
                mMatrixMessageListFragment.sendEmote(body.substring(4));
            }
            else {
                mMatrixMessageListFragment.sendMessage(body);
            }
        }

    }

}