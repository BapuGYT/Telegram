package org.telegram.messenger;

import android.os.AsyncTask;
import android.os.Handler;

import com.aniways.Log;
import com.aniways.Utils;

import org.telegram.android.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

import java.util.concurrent.RejectedExecutionException;

import static org.telegram.messenger.TLRPC.TL_messages_sendMessage;

/**
 * Created by danielkalman on 5/27/14.
 */
public class NotificationSenderTask extends AsyncTask<String, String, String> {
    public static final String NOTIFICATION_URL = "http://obscure-headland-7367.herokuapp.com/sendNotification";

    @Override
    protected String doInBackground(String... params) {
        try {
            final int user_id = Integer.parseInt(params[0]);
            final int chat_id = Integer.parseInt(params[1]);
            String senderName = params[2];
            boolean isAttachment = Boolean.parseBoolean(params[3]);
            final String messageText = isAttachment ? params[4] + " received from " + senderName : senderName + " says " + params[4];

            if (user_id != 0) {
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        if(Utils.isAndroidVersionAtLeast(11)){
                            new RequestTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, NOTIFICATION_URL, String.valueOf(user_id), messageText);
                        } else {
                            new RequestTask().execute(NOTIFICATION_URL, String.valueOf(user_id), messageText);
                        }
                    }
                });

            } else {
                TLRPC.TL_messages_getFullChat getFullChat = new TLRPC.TL_messages_getFullChat();
                getFullChat.chat_id = chat_id;
                ConnectionsManager.getInstance().performRpc(getFullChat, new RPCRequest.RPCRequestDelegate() {
                    @Override
                    public void run(TLObject response, TLRPC.TL_error error) {
                        if (error == null) {
                            final TLRPC.ChatParticipants participants = ((TLRPC.TL_messages_chatFull) response).full_chat.participants;
                            // TODO: this is a hack because of the RejectedExecutionException
                            if(participants.participants.size() > 30){
                                return;
                            }
                            for (final TLRPC.TL_chatParticipant cp : participants.participants) {
                                AndroidUtilities.runOnUIThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            if (Utils.isAndroidVersionAtLeast(11)) {
                                                new RequestTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, NOTIFICATION_URL, String.valueOf(cp.user_id), messageText);
                                            } else {
                                                new RequestTask().execute(NOTIFICATION_URL, String.valueOf(cp.user_id), messageText);
                                            }
                                        } catch (RejectedExecutionException ex) {
                                            Log.e(true, "NotificationSenderTask", "Caught rejected execution exception while trying to start the Request task. Number of participants: " + participants.participants.size(), ex);
                                        }
                                    }
                                });
                                }
                        }

                    }
                }, true, RPCRequest.RPCRequestClassGeneric | RPCRequest.RPCRequestClassFailOnServerErrors | RPCRequest.RPCRequestClassCanCompress);
            }
        } catch (Exception e){

        }

        return null;
    }
}
