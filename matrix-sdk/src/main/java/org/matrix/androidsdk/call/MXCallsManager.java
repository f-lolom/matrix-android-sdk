/*
 * Copyright 2015 OpenMarket Ltd
 * Copyright 2017 Vector Creations Ltd
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.androidsdk.call;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;

import org.matrix.androidsdk.crypto.MXCryptoError;
import org.matrix.androidsdk.crypto.data.MXDeviceInfo;
import org.matrix.androidsdk.crypto.data.MXUsersDevicesMap;
import org.matrix.androidsdk.rest.callback.SimpleApiCallback;
import org.matrix.androidsdk.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.listeners.MXEventListener;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.client.CallRestClient;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.EventContent;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.util.JsonUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

public class MXCallsManager {
    private static final String LOG_TAG = "MXCallsManager";

    public interface MXCallsManagerListener {
        /**
         * Called when there is an incoming call within the room.
         * @param call the incoming call
         * @param unknownDevices the unknown e2e devices list
         */
        void onIncomingCall(IMXCall call, MXUsersDevicesMap<MXDeviceInfo> unknownDevices);

        /**
         * Called when a called has been hung up
         * @param call the incoming call
         */
        void onCallHangUp(IMXCall call);

        /**
         * A voip conference started in a room.
         * @param roomId the room id
         */
        void onVoipConferenceStarted(String roomId);

        /**
         * A voip conference finished in a room.
         * @param roomId the room id
         */
        void onVoipConferenceFinished(String roomId);
    }

    /**
     * Defines the call classes.
     */
    public enum CallClass {
        CHROME_CLASS,
        JINGLE_CLASS,
        DEFAULT_CLASS
    }

    private MXSession mSession = null;
    private Context mContext = null;

    private CallRestClient mCallResClient = null;
    private JsonElement mTurnServer = null;
    private Timer mTurnServerTimer = null;
    private boolean mSuspendTurnServerRefresh = false;

    private CallClass mPreferredCallClass = CallClass.JINGLE_CLASS;

    // active calls
    private final HashMap<String, IMXCall> mCallsByCallId = new HashMap<>();

    // listeners
    private final ArrayList<MXCallsManagerListener> mListeners = new ArrayList<>();

    // incoming calls
    private final ArrayList<String> mxPendingIncomingCallId = new ArrayList<>();

    // UI handler
    private final Handler mUIThreadHandler;

    /**
     * Constructor
     * @param session the session
     * @param context the context
     */
    public MXCallsManager(MXSession session, Context context) {
        mSession = session;
        mContext = context;

        mUIThreadHandler = new Handler(Looper.getMainLooper());

        mCallResClient = mSession.getCallRestClient();

        mSession.getDataHandler().addListener(new MXEventListener() {
            @Override
            public void onLiveEvent(Event event, RoomState roomState) {
                if (TextUtils.equals(event.getType(), Event.EVENT_TYPE_STATE_ROOM_MEMBER)) {
                    // Listen to the membership join/leave events to detect the conference user activity.
                    // This mechanism detects the presence of an established conf call
                    if (TextUtils.equals(event.sender, MXCallsManager.getConferenceUserId(event.roomId))) {
                        EventContent eventContent = JsonUtils.toEventContent(event.getContentAsJsonObject());

                        if (TextUtils.equals(eventContent.membership, RoomMember.MEMBERSHIP_LEAVE)) {
                            dispatchOnVoipConferenceFinished(event.roomId);
                        } if (TextUtils.equals(eventContent.membership, RoomMember.MEMBERSHIP_JOIN)) {
                            dispatchOnVoipConferenceStarted(event.roomId);
                        }
                    }
                }
            }
        });

        refreshTurnServer();
    }

    /**
     * @return true if the call feature is supported
     */
    public boolean isSupported() {
        return MXChromeCall.isSupported() || MXJingleCall.isSupported(mContext);
    }

    /**
     * @return the list of supported classes
     */
    public Collection<CallClass> supportedClass() {
        ArrayList<CallClass> list = new ArrayList<>();

        if (MXChromeCall.isSupported()) {
            list.add(CallClass.CHROME_CLASS);
        }

        if (MXJingleCall.isSupported(mContext)) {
            list.add(CallClass.JINGLE_CLASS);
        }

        Log.d(LOG_TAG, "supportedClass " + list);

        return list;
    }

    /**
     * @param callClass set the default callClass
     */
    public void setDefaultCallClass(CallClass callClass) {
        Log.d(LOG_TAG, "setDefaultCallClass " + callClass);

        boolean isUpdatable = false;

        if (callClass == CallClass.CHROME_CLASS) {
            isUpdatable = MXChromeCall.isSupported();
        }

        if (callClass == CallClass.JINGLE_CLASS) {
            isUpdatable = MXJingleCall.isSupported(mContext);
        }

        if (isUpdatable) {
            mPreferredCallClass = callClass;
        }
    }

    /**
     * create a new call
     * @param callId the call Id (null to use a default value)
     * @return the IMXCall
     */
    private IMXCall createCall(String callId) {
        Log.d(LOG_TAG, "createCall " + callId);

        IMXCall call = null;

        // default
        if (((CallClass.CHROME_CLASS == mPreferredCallClass) || (CallClass.DEFAULT_CLASS == mPreferredCallClass)) && MXChromeCall.isSupported()) {
            call = new MXChromeCall(mSession, mContext, getTurnServer());
        }

        // Jingle
        if (null == call) {
            try {
                call = new MXJingleCall(mSession, mContext, getTurnServer());
            } catch (Exception e) {
                Log.e(LOG_TAG, "createCall " + e.getLocalizedMessage());
            }
        }

        // a valid callid is provided
        if (null != callId) {
            call.setCallId(callId);
        }

        return call;
    }

    /**
     * Search a call from its dedicated room id.
     * @param roomId the room id
     * @return the IMXCall if it exists
     */
    public IMXCall getCallWithRoomId(String roomId) {
        ArrayList<IMXCall> calls;

        synchronized (this) {
            calls = new ArrayList<>(mCallsByCallId.values());
        }

        for(IMXCall call : calls) {
            if (TextUtils.equals(roomId, call.getRoom().getRoomId())) {
                if (TextUtils.equals(call.getCallState(), IMXCall.CALL_STATE_ENDED)) {
                    Log.d(LOG_TAG, "## getCallWithRoomId() : the call " +  call.getCallId() + " has been stopped");
                    synchronized (this) {
                        mCallsByCallId.remove(call.getCallId());
                    }
                } else {
                    return call;
                }
            }
        }

        return null;
    }

    /**
     * Returns the IMXCall from its callId.
     * @param callId the call Id
     * @return the IMXCall if it exists
     */
    public IMXCall getCallWithCallId(String callId) {
        return getCallWithCallId(callId, false);
    }

    /**
     * Returns the IMXCall from its callId.
     * @param callId the call Id
     * @param create create the IMXCall if it does not exist
     * @return the IMXCall if it exists
     */
    private IMXCall getCallWithCallId(String callId, boolean create) {
        IMXCall call = null;

        // check if the call exists
        if (null != callId) {
            synchronized (this) {
                call = mCallsByCallId.get(callId);
            }
        }

        // test if the call has been stopped
        if ((null != call) && TextUtils.equals(call.getCallState(), IMXCall.CALL_STATE_ENDED)) {
            Log.d(LOG_TAG, "## getCallWithCallId() : the call " + callId + " has been stopped");
            synchronized (this) {
                mCallsByCallId.remove(call.getCallId());
            }

            call = null;
        }

        // the call does not exist but request to create it
        if ((null == call) && create) {
            call = createCall(callId);
            synchronized (this) {
                mCallsByCallId.put(call.getCallId(), call);
            }
        }

        Log.d(LOG_TAG, "getCallWithCallId " + callId + " " + call);

        return call;
    }

    /**
     * Tell if a call is in progress
     * @return true if the call is in progress
     */
    public static boolean isCallInProgress(IMXCall call) {
        boolean res = false;

        if (null != call) {
            String callState = call.getCallState();
            res =
                    TextUtils.equals(callState, IMXCall.CALL_STATE_CREATED) ||
                            TextUtils.equals(callState, IMXCall.CALL_STATE_CREATING_CALL_VIEW) ||
                            TextUtils.equals(callState, IMXCall.CALL_STATE_FLEDGLING) ||
                            TextUtils.equals(callState, IMXCall.CALL_STATE_WAIT_LOCAL_MEDIA) ||
                            TextUtils.equals(callState, IMXCall.CALL_STATE_WAIT_CREATE_OFFER) ||
                            TextUtils.equals(callState, IMXCall.CALL_STATE_INVITE_SENT) ||
                            TextUtils.equals(callState, IMXCall.CALL_STATE_RINGING) ||
                            TextUtils.equals(callState, IMXCall.CALL_STATE_CREATE_ANSWER) ||
                            TextUtils.equals(callState, IMXCall.CALL_STATE_RINGING) ||
                            TextUtils.equals(callState, IMXCall.CALL_STATE_CONNECTING) ||
                            TextUtils.equals(callState, IMXCall.CALL_STATE_CONNECTED);
        }

        return res;
    }

    /**
     * @return true if there are some active calls.
     */
    public boolean hasActiveCalls() {
        synchronized (this) {
            ArrayList<String> callIdsToRemove = new ArrayList<>();

            Set<String> callIds = mCallsByCallId.keySet();

            for(String callId : callIds) {
                IMXCall call = mCallsByCallId.get(callId);

                if (TextUtils.equals(call.getCallState(), IMXCall.CALL_STATE_ENDED)) {
                    Log.d(LOG_TAG, "# hasActiveCalls() : the call " + callId + " is not anymore valid");
                    callIdsToRemove.add(callId);
                } else {
                    Log.d(LOG_TAG, "# hasActiveCalls() : the call " + callId + " is active");
                    return true;
                }
            }

            for (String callIdToRemove : callIdsToRemove) {
                mCallsByCallId.remove(callIdToRemove);
            }
        }

        Log.d(LOG_TAG, "# hasActiveCalls() : no active call");
        return false;
    }

    /**
     * Manage the call events.
     * @param event the call event.
     */
    public void handleCallEvent(final Event event) {
        if (event.isCallEvent() && isSupported()) {
            Log.d(LOG_TAG, "handleCallEvent " + event.getType());

            // always run the call event in the UI thread
            // MXChromeCall does not work properly in other thread (because of the webview)
            mUIThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    boolean isMyEvent = TextUtils.equals(event.getSender(), mSession.getMyUserId());
                    Room room = mSession.getDataHandler().getRoom(event.roomId);

                    String callId = null;
                    JsonObject eventContent = null;

                    try {
                        eventContent = event.getContentAsJsonObject();
                        callId = eventContent.getAsJsonPrimitive("call_id").getAsString();
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "handleCallEvent : fail to retrieve call_id " + e.getMessage());
                    }
                    // sanity check
                    if ((null != callId) && (null != room)) {
                        // receive an invitation
                        if (Event.EVENT_TYPE_CALL_INVITE.equals(event.getType())) {
                            long lifeTime = event.getAge();

                            if (Long.MAX_VALUE == lifeTime) {
                                lifeTime = System.currentTimeMillis() - event.getOriginServerTs();
                            }

                            // ignore older call messages
                            if (lifeTime < 30000) {
                                // create the call only it is triggered from someone else
                                IMXCall call = getCallWithCallId(callId, !isMyEvent);

                                // sanity check
                                if (null != call) {
                                    // init the information
                                    if (null == call.getRoom()) {
                                        call.setRooms(room, room);
                                    }

                                    if (!isMyEvent) {
                                        call.prepareIncomingCall(eventContent, callId, null);
                                        call.setIsIncoming(true);
                                        mxPendingIncomingCallId.add(callId);
                                    } else {
                                        call.handleCallEvent(event);
                                    }
                                }
                            } else {
                                Log.d(LOG_TAG, "## handleCallEvent() : " + Event.EVENT_TYPE_CALL_INVITE + " is ignored because it is too old");
                            }
                        } else if (Event.EVENT_TYPE_CALL_CANDIDATES.equals(event.getType())) {
                            if (!isMyEvent) {
                                IMXCall call = getCallWithCallId(callId);

                                if (null != call) {
                                    if (null == call.getRoom()) {
                                        call.setRooms(room, room);
                                    }
                                    call.handleCallEvent(event);
                                }
                            }
                        } else if (Event.EVENT_TYPE_CALL_ANSWER.equals(event.getType())) {
                            IMXCall call = getCallWithCallId(callId);

                            if (null != call) {
                                // assume it is a catch up call.
                                // the creation / candidates /
                                // the call has been answered on another device
                                if (IMXCall.CALL_STATE_CREATED.equals(call.getCallState())) {
                                    call.onAnsweredElsewhere();
                                    synchronized (this) {
                                        mCallsByCallId.remove(callId);
                                    }
                                } else {
                                    if (null == call.getRoom()) {
                                        call.setRooms(room, room);
                                    }
                                    call.handleCallEvent(event);
                                }
                            }
                        } else if (Event.EVENT_TYPE_CALL_HANGUP.equals(event.getType())) {
                            final IMXCall call = getCallWithCallId(callId);
                            if (null != call) {
                                // trigger call events only if the call is active
                                final boolean isActiveCall = !IMXCall.CALL_STATE_CREATED.equals(call.getCallState());

                                if (null == call.getRoom()) {
                                    call.setRooms(room, room);
                                }

                                if (isActiveCall) {
                                    call.handleCallEvent(event);
                                }

                                synchronized (this) {
                                    mCallsByCallId.remove(callId);
                                }

                                // warn that a call has been hung up
                                mUIThreadHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        // must warn anyway any listener that the call has been killed
                                        // for example, when the device is in locked screen
                                        // the callview is not created but the device is ringing
                                        // if the other participant ends the call, the ring should stop
                                        dispatchOnCallHangUp(call);
                                    }
                                });
                            }
                        }
                    }
                }
            });
        }
    }

    /**
     * check if there is a pending incoming call
     */
    public void checkPendingIncomingCalls() {
        Log.d(LOG_TAG, "checkPendingIncomingCalls");

        mUIThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mxPendingIncomingCallId.size() > 0) {
                    for (String callId : mxPendingIncomingCallId) {
                        final IMXCall call = getCallWithCallId(callId);

                        if (null != call) {
                            final Room room = call.getRoom();

                            // for encrypted rooms with 2 members
                            // check if there are some unknown devices before warning
                            // of the incoming call.
                            // If there are some unknown devices, the answer event would not be encrypted.
                            if ((null != room) &&
                                    room.isEncrypted() &&
                                    mSession.getCrypto().warnOnUnknownDevices() &&
                                    (room.getJoinedMembers().size() == 2)) {

                                // test if the encrypted events are sent only to the verified devices (any room)
                                mSession.getCrypto().getGlobalBlacklistUnverifiedDevices(new SimpleApiCallback<Boolean>() {
                                    @Override
                                    public void onSuccess(Boolean sendToVerifiedDevicesOnly) {
                                        if (sendToVerifiedDevicesOnly) {
                                            dispatchOnIncomingCall(call, null);
                                        } else {
                                            //  test if the encrypted events are sent only to the verified devices (only this room)
                                            mSession.getCrypto().isRoomBlacklistUnverifiedDevices(room.getRoomId(), new SimpleApiCallback<Boolean>() {
                                                @Override
                                                public void onSuccess(Boolean sendToVerifiedDevicesOnly) {
                                                    if (sendToVerifiedDevicesOnly) {
                                                        dispatchOnIncomingCall(call, null);
                                                    } else {
                                                        List<RoomMember> members = new ArrayList<>(room.getJoinedMembers());
                                                        String userId1 = members.get(0).getUserId();
                                                        String userId2 = members.get(1).getUserId();

                                                        Log.d(LOG_TAG, "## checkPendingIncomingCalls() : check the unknown devices");

                                                        //
                                                        mSession.getCrypto().checkUnknownDevices(Arrays.asList(userId1, userId2), new ApiCallback<Void>() {
                                                            @Override
                                                            public void onSuccess(Void anything) {
                                                                Log.d(LOG_TAG, "## checkPendingIncomingCalls() : no unknown device");
                                                                dispatchOnIncomingCall(call, null);
                                                            }

                                                            @Override
                                                            public void onNetworkError(Exception e) {
                                                                Log.e(LOG_TAG, "## checkPendingIncomingCalls() : checkUnknownDevices failed " + e.getMessage());
                                                                dispatchOnIncomingCall(call, null);
                                                            }

                                                            @Override
                                                            public void onMatrixError(MatrixError e) {
                                                                MXUsersDevicesMap<MXDeviceInfo> unknownDevices = null;

                                                                if (e instanceof MXCryptoError) {
                                                                    MXCryptoError cryptoError = (MXCryptoError) e;

                                                                    if (MXCryptoError.UNKNOWN_DEVICES_CODE.equals(cryptoError.errcode)) {
                                                                        unknownDevices = (MXUsersDevicesMap<MXDeviceInfo>) cryptoError.mExceptionData;
                                                                    }
                                                                }

                                                                if (null != unknownDevices) {
                                                                    Log.d(LOG_TAG, "## checkPendingIncomingCalls() : checkUnknownDevices found some unknown devices");
                                                                } else {
                                                                    Log.e(LOG_TAG, "## checkPendingIncomingCalls() : checkUnknownDevices failed " + e.getMessage());
                                                                }

                                                                dispatchOnIncomingCall(call, unknownDevices);
                                                            }

                                                            @Override
                                                            public void onUnexpectedError(Exception e) {
                                                                Log.e(LOG_TAG, "## checkPendingIncomingCalls() : checkUnknownDevices failed " + e.getMessage());
                                                                dispatchOnIncomingCall(call, null);
                                                            }
                                                        });
                                                    }
                                                }
                                            });
                                        }
                                    }
                                });
                            } else {
                                dispatchOnIncomingCall(call, null);
                            }
                        }
                    }
                }
                mxPendingIncomingCallId.clear();
            }
        });
    }

    /**
     * Create an IMXCall in the room defines by its room Id.
     * -> for a 1:1 call, it is a standard call.
     * -> for a conference call,
     * ----> the conference user is invited to the room (if it was not yet invited)
     * ----> the call signaling room is created (or retrieved) with the conference
     * ----> and the call is started
     *
     * @param roomId the room roomId
     * @param callback the async callback
     */
    public void createCallInRoom(final String roomId, final ApiCallback<IMXCall> callback) {
        Log.d(LOG_TAG, "createCallInRoom in " + roomId);

        final Room room = mSession.getDataHandler().getRoom(roomId);

        // sanity check
        if (null != room) {
            if (isSupported()) {
                int joinedMembers = room.getJoinedMembers().size();

                Log.d(LOG_TAG, "createCallInRoom : the room has " + joinedMembers + " joined members");

                if (joinedMembers > 1) {
                    if (joinedMembers == 2) {
                        // when a room is encrypted, test first there is no unknown device
                        // else the call will fail.
                        // So it seems safer to reject the call creation it it will fail.
                        if (room.isEncrypted() && mSession.getCrypto().warnOnUnknownDevices()) {
                            List<RoomMember> members = new ArrayList<>(room.getJoinedMembers());
                            String userId1 = members.get(0).getUserId();
                            String userId2 = members.get(1).getUserId();

                            // force the refresh to ensure that the devices list is up-to-date
                            mSession.getCrypto().checkUnknownDevices(Arrays.asList(userId1, userId2), new ApiCallback<Void>() {
                                @Override
                                public void onSuccess(Void anything) {
                                    final IMXCall call = getCallWithCallId(null, true);
                                    call.setRooms(room, room);

                                    if (null != callback) {
                                        mUIThreadHandler.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                callback.onSuccess(call);
                                            }
                                        });
                                    }
                                }

                                @Override
                                public void onNetworkError(Exception e) {
                                    if (null != callback) {
                                        callback.onNetworkError(e);
                                    }
                                }

                                @Override
                                public void onMatrixError(MatrixError e) {
                                    if (null != callback) {
                                        callback.onMatrixError(e);
                                    }
                                }

                                @Override
                                public void onUnexpectedError(Exception e) {
                                    if (null != callback) {
                                        callback.onUnexpectedError(e);
                                    }
                                }
                            });
                        } else {
                            final IMXCall call = getCallWithCallId(null, true);
                            call.setRooms(room, room);

                            if (null != callback) {
                                mUIThreadHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        callback.onSuccess(call);
                                    }
                                });
                            }
                        }
                    } else {
                        Log.d(LOG_TAG, "createCallInRoom : inviteConferenceUser");

                        inviteConferenceUser(room, new ApiCallback<Void>() {
                            @Override
                            public void onSuccess(Void info) {
                                Log.d(LOG_TAG, "createCallInRoom : inviteConferenceUser succeeds");

                                getConferenceUserRoom(room.getRoomId(), new ApiCallback<Room>() {
                                    @Override
                                    public void onSuccess(Room conferenceRoom) {

                                        Log.d(LOG_TAG, "createCallInRoom : getConferenceUserRoom succeeds");

                                        final IMXCall call = getCallWithCallId(null, true);
                                        call.setRooms(room, conferenceRoom);
                                        call.setIsConference(true);

                                        if (null != callback) {
                                            mUIThreadHandler.post(new Runnable() {
                                                @Override
                                                public void run() {
                                                    callback.onSuccess(call);
                                                }
                                            });
                                        }
                                    }

                                    @Override
                                    public void onNetworkError(Exception e) {
                                        Log.d(LOG_TAG, "createCallInRoom : getConferenceUserRoom failed " + e.getLocalizedMessage());

                                        if (null != callback) {
                                            callback.onNetworkError(e);
                                        }
                                    }

                                    @Override
                                    public void onMatrixError(MatrixError e) {
                                        Log.d(LOG_TAG, "createCallInRoom : getConferenceUserRoom failed " + e.getLocalizedMessage());


                                        if (null != callback) {
                                            callback.onMatrixError(e);
                                        }
                                    }

                                    @Override
                                    public void onUnexpectedError(Exception e) {
                                        Log.d(LOG_TAG, "createCallInRoom : getConferenceUserRoom failed " + e.getLocalizedMessage());

                                        if (null != callback) {
                                            callback.onUnexpectedError(e);
                                        }
                                    }
                                });
                            }

                            @Override
                            public void onNetworkError(Exception e) {
                                Log.d(LOG_TAG, "createCallInRoom : inviteConferenceUser fails " + e.getLocalizedMessage());

                                if (null != callback) {
                                    callback.onNetworkError(e);
                                }
                            }

                            @Override
                            public void onMatrixError(MatrixError e) {
                                Log.d(LOG_TAG, "createCallInRoom : inviteConferenceUser fails " + e.getLocalizedMessage());

                                if (null != callback) {
                                    callback.onMatrixError(e);
                                }
                            }

                            @Override
                            public void onUnexpectedError(Exception e) {
                                Log.d(LOG_TAG, "createCallInRoom : inviteConferenceUser fails " + e.getLocalizedMessage());

                                if (null != callback) {
                                    callback.onUnexpectedError(e);
                                }
                            }
                        });
                    }
                } else {
                    if (null != callback) {
                        callback.onMatrixError(new MatrixError(MatrixError.NOT_SUPPORTED, "too few users"));
                    }
                }
            } else {
                if (null != callback) {
                    callback.onMatrixError(new MatrixError(MatrixError.NOT_SUPPORTED, "VOIP is not supported"));
                }
            }
        } else {
            if (null != callback) {
                callback.onMatrixError(new MatrixError(MatrixError.NOT_FOUND, "room not found"));
            }
        }
    }

    //==============================================================================================================
    // Turn servers management
    //==============================================================================================================

    /**
     * Suspend the turn server  refresh
     */
    public void pauseTurnServerRefresh() {
        mSuspendTurnServerRefresh = true;
    }

    /**
     * Refresh the turn servers until it succeeds.
     */
    public void unpauseTurnServerRefresh() {
        Log.d(LOG_TAG, "unpauseTurnServerRefresh");

        mSuspendTurnServerRefresh = false;
        if (null != mTurnServerTimer) {
            mTurnServerTimer.cancel();
            mTurnServerTimer = null;
        }
        refreshTurnServer();
    }

    /**
     * Stop the turn servers refresh.
     */
    public void stopTurnServerRefresh() {
        Log.d(LOG_TAG, "stopTurnServerRefresh");

        mSuspendTurnServerRefresh = true;
        if (null != mTurnServerTimer) {
            mTurnServerTimer.cancel();
            mTurnServerTimer = null;
        }
    }

    /**
     * @return the turn server
     */
    private JsonElement getTurnServer() {
        JsonElement res;

        synchronized (LOG_TAG) {
            res = mTurnServer;
        }

        // privacy logs
        //Log.d(LOG_TAG, "getTurnServer " + res);
        Log.d(LOG_TAG, "getTurnServer ");

        return res;
    }

    /**
     * Refresh the turn servers.
     */
    private void refreshTurnServer() {
        if (mSuspendTurnServerRefresh) {
            return;
        }

        Log.d(LOG_TAG, "refreshTurnServer");

        mUIThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                mCallResClient.getTurnServer(new ApiCallback<JsonObject>() {
                    private void restartAfter(int msDelay) {
                        if (null != mTurnServerTimer) {
                            mTurnServerTimer.cancel();
                        }

                        mTurnServerTimer = new Timer();
                        mTurnServerTimer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                Log.d(LOG_TAG, "refreshTurnServer cancelled");
                                mTurnServerTimer.cancel();
                                mTurnServerTimer = null;

                                refreshTurnServer();
                            }
                        }, msDelay);
                    }

                    @Override
                    public void onSuccess(JsonObject info) {
                        // privacy
                        Log.d(LOG_TAG, "onSuccess ");
                        //Log.d(LOG_TAG, "onSuccess " + info);

                        if (null != info) {
                            if (info.has("uris")) {
                                synchronized (LOG_TAG) {
                                    mTurnServer = info;
                                }
                            }

                            if (info.has("ttl")) {
                                int ttl = 60000;

                                try {
                                    ttl = info.get("ttl").getAsInt();
                                    // restart a 90 % before ttl expires
                                    ttl = ttl * 9 / 10;
                                } catch (Exception e) {
                                    Log.e(LOG_TAG, "Fail to retrieve ttl " + e.getMessage());
                                }

                                restartAfter(ttl);
                            }
                        }
                    }

                    @Override
                    public void onNetworkError(Exception e) {
                        restartAfter(60000);
                    }

                    @Override
                    public void onMatrixError(MatrixError e) {
                        if (TextUtils.equals(e.errcode, MatrixError.LIMIT_EXCEEDED)) {
                            restartAfter(60000);
                        }
                    }

                    @Override
                    public void onUnexpectedError(Exception e) {
                        // should never happen
                    }
                });
            }
        });
    }

    //==============================================================================================================
    // Conference call
    //==============================================================================================================


    // Copied from vector-web:
    // FIXME: This currently forces Vector to try to hit the matrix.org AS for conferencing.
    // This is bad because it prevents people running their own ASes from being used.
    // This isn't permanent and will be customisable in the future: see the proposal
    // at docs/conferencing.md for more info.
    private static final String USER_PREFIX = "fs_";
    private static final String DOMAIN = "matrix.org";
    private static final HashMap<String, String> mConferenceUserIdByRoomId = new HashMap<>();
    /**
     * Return the id of the conference user dedicated for a room Id
     * @param roomId the room id
     * @return the conference user id
     */
    public static String getConferenceUserId(String roomId) {
        // sanity check
        if (null == roomId) {
            return null;
        }

        String conferenceUserId = mConferenceUserIdByRoomId.get(roomId);

        // it does not exist, compute it.
        if (null == conferenceUserId) {
            byte[] data = null;

            try {
                data = roomId.getBytes("UTF-8");
            } catch (Exception e) {
                Log.e(LOG_TAG, "conferenceUserIdForRoom failed " + e.getMessage());
            }

            if (null == data) {
                return null;
            }

            String base64 = Base64.encodeToString(data, Base64.NO_WRAP | Base64.URL_SAFE).replace("=", "");
            conferenceUserId = "@" + USER_PREFIX + base64 + ":" + DOMAIN;

            mConferenceUserIdByRoomId.put(roomId, conferenceUserId);
        }

        return conferenceUserId;
    }

    /**
     * Test if the provided user is a valid conference user Id
     * @param userId the user id to test
     * @return true if it is a valid conference user id
     */
    public static boolean isConferenceUserId(String userId) {
        // test first if it a known conference user id
        if (mConferenceUserIdByRoomId.values().contains(userId)) {
            return true;
        }

        boolean res = false;

        String prefix = "@" + USER_PREFIX;
        String suffix = ":" + DOMAIN;

        if (!TextUtils.isEmpty(userId) && userId.startsWith(prefix) && userId.endsWith(suffix)) {
            String roomIdBase64 = userId.substring(prefix.length(), userId.length() - suffix.length());
            try {
                res = MXSession.isRoomId((new String(Base64.decode(roomIdBase64, Base64.NO_WRAP | Base64.URL_SAFE), "UTF-8")));
            } catch (Exception e) {
                Log.e(LOG_TAG, "isConferenceUserId : failed " + e.getMessage());
            }
        }

        return res;
    }

    /**
     * Invite the conference user to a room.
     * It is mandatory before starting a conference call.
     * @param room the room
     * @param callback the async callback
     */
    private void inviteConferenceUser(final Room room, final ApiCallback<Void> callback) {
        Log.d(LOG_TAG, "inviteConferenceUser " + room.getRoomId());

        String conferenceUserId = getConferenceUserId(room.getRoomId());
        RoomMember conferenceMember = room.getMember(conferenceUserId);

        if ((null != conferenceMember) && TextUtils.equals(conferenceMember.membership, RoomMember.MEMBERSHIP_JOIN)) {
            mUIThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    callback.onSuccess(null);
                }
            });
        } else {
            room.invite(conferenceUserId, callback);
        }
    }

    /**
     * Get the room with the conference user dedicated for the passed room.
     * @param roomId the room id.
     * @param callback the async callback.
     */
    private void getConferenceUserRoom(final String roomId, final ApiCallback<Room> callback) {
        Log.d(LOG_TAG, "getConferenceUserRoom with room id " + roomId);

        String conferenceUserId = getConferenceUserId(roomId);

        Room conferenceRoom = null;
        Collection<Room> rooms = mSession.getDataHandler().getStore().getRooms();

        // Use an existing 1:1 with the conference user; else make one
        for(Room room : rooms) {
            if (room.isConferenceUserRoom() && (2 == room.getMembers().size()) && (null != room.getMember(conferenceUserId))) {
                conferenceRoom = room;
                break;
            }
        }

        if (null != conferenceRoom) {
            Log.d(LOG_TAG, "getConferenceUserRoom : the room already exists");

            final Room fConferenceRoom = conferenceRoom;
            mSession.getDataHandler().getStore().commit();

            mUIThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    callback.onSuccess(fConferenceRoom);
                }
            });
        } else {
            Log.d(LOG_TAG, "getConferenceUserRoom : create the room");

            HashMap<String, Object> params = new HashMap<>();
            params.put("preset", "private_chat");
            params.put("invite", Arrays.asList(conferenceUserId));

            mSession.createRoom(params, new ApiCallback<String>() {
                @Override
                public void onSuccess(String roomId) {
                    Log.d(LOG_TAG, "getConferenceUserRoom : the room creation succeeds");

                    Room room = mSession.getDataHandler().getRoom(roomId);

                    if (null != room) {
                        room.setIsConferenceUserRoom(true);
                        mSession.getDataHandler().getStore().commit();
                        callback.onSuccess(room);
                    }
                }

                @Override
                public void onNetworkError(Exception e) {
                    Log.d(LOG_TAG, "getConferenceUserRoom : failed " + e.getMessage());
                    callback.onNetworkError(e);
                }

                @Override
                public void onMatrixError(MatrixError e) {
                    Log.d(LOG_TAG, "getConferenceUserRoom : failed " + e.getLocalizedMessage());
                    callback.onMatrixError(e);
                }

                @Override
                public void onUnexpectedError(Exception e) {
                    Log.d(LOG_TAG, "getConferenceUserRoom : failed " + e.getLocalizedMessage());
                    callback.onUnexpectedError(e);
                }
            });
        }
    }

    //==============================================================================================================
    // listeners management
    //==============================================================================================================

    /**
     * Add a listener
     * @param listener the listener to add
     */
    public void addListener(MXCallsManagerListener listener) {
        if (null != listener) {
            synchronized (this) {
                if (mListeners.indexOf(listener) < 0) {
                    mListeners.add(listener);
                }
            }
        }
    }

    /**
     * Remove a listener
     * @param listener the listener to remove
     */
    public void removeListener(MXCallsManagerListener listener) {
        if (null != listener) {
            synchronized (this) {
                mListeners.remove(listener);
            }
        }
    }

    /**
     * @return a copy of the listeners
     */
    private List<MXCallsManagerListener> getListeners() {
        ArrayList<MXCallsManagerListener> listeners;

        synchronized (this) {
            listeners = new ArrayList<>(mListeners);
        }

        return listeners;
    }

    /**
     * dispatch the onIncomingCall event to the listeners
     * @param call the call
     * @param unknownDevices the unknown e2e devices list.
     */
    private void dispatchOnIncomingCall(IMXCall call, final MXUsersDevicesMap<MXDeviceInfo> unknownDevices) {
        Log.d(LOG_TAG, "dispatchOnIncomingCall " + call.getCallId());

        List<MXCallsManagerListener> listeners = getListeners();

        for(MXCallsManagerListener l : listeners) {
            try {
                l.onIncomingCall(call, unknownDevices);
            } catch (Exception e) {
                Log.e(LOG_TAG, "dispatchOnIncomingCall " + e.getMessage());
            }
        }
    }

    /**
     * dispatch the onCallHangUp event to the listeners
     * @param call the call
     */
    private void dispatchOnCallHangUp(IMXCall call) {
        Log.d(LOG_TAG, "dispatchOnCallHangUp");

        List<MXCallsManagerListener> listeners = getListeners();

        for(MXCallsManagerListener l : listeners) {
            try {
                l.onCallHangUp(call);
            } catch (Exception e) {
                Log.e(LOG_TAG, "dispatchOnCallHangUp " + e.getMessage());
            }
        }
    }

    /**
     * dispatch the onVoipConferenceStarted event to the listeners
     * @param roomId the room Id
     */
    private void dispatchOnVoipConferenceStarted(String roomId) {
        Log.d(LOG_TAG, "dispatchOnVoipConferenceStarted : " + roomId);

        List<MXCallsManagerListener> listeners = getListeners();

        for(MXCallsManagerListener l : listeners) {
            try {
                l.onVoipConferenceStarted(roomId);
            } catch (Exception e) {
                Log.e(LOG_TAG, "dispatchOnVoipConferenceStarted " + e.getMessage());
            }
        }
    }

    /**
     * dispatch the onVoipConferenceFinished event to the listeners
     * @param roomId the room Id
     */
    private void dispatchOnVoipConferenceFinished(String roomId) {
        Log.d(LOG_TAG, "onVoipConferenceFinished : " + roomId);

        List<MXCallsManagerListener> listeners = getListeners();

        for(MXCallsManagerListener l : listeners) {
                try {
                    l.onVoipConferenceFinished(roomId);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "dispatchOnVoipConferenceFinished " + e.getMessage());
                }
            }

    }
}
