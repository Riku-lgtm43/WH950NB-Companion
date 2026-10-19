package com.xriku.edifiercontrol;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide single-file Bluetooth transaction queue. No always-on service or polling. */
public final class BluetoothController {
    private BluetoothController() {}
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    // The queue is touched on the main thread only. RFCOMM work happens on WORKER.
    private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();
    private static boolean active;
    private static long sequence;
    private static volatile long latestNoiseSequence;

    public interface Callback { void done(boolean success, String message); }
    public interface StateCallback { void done(boolean success, String message, Protocol.State state); }
    public interface DetailedCallback { void done(Outcome outcome); }
    /** Called after sending bytes, before reading actual headphone state. */
    public interface StageCallback { void changed(String stage); }
    public static final class Outcome {
        public final boolean sent;
        public final String verdict;
        public final String message;
        public final Protocol.State state;
        Outcome(boolean sent, String verdict, String message, Protocol.State state) {
            this.sent=sent; this.verdict=verdict; this.message=message; this.state=state;
        }
    }
    private static final class Job {
        Context context;
        String action;
        DetailedCallback callback;
        StageCallback stageCallback;
        StateCallback stateCallback;
        boolean readOnly;
        boolean noise;
        boolean slider;
        long id;
        long noiseVersion;
    }

    public static boolean permitted(Context c) {
        return Build.VERSION.SDK_INT < 31 || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }
    public static void send(Context c, String action, Callback callback) {
        sendVerified(c,action, result -> {
            if (callback!=null) callback.done(result.sent,result.message);
        });
    }
    public static void sendVerified(Context c, String action, DetailedCallback callback) {
        sendVerified(c, action, null, callback);
    }
    public static void sendVerified(Context c, String action, StageCallback stageCallback,
                                    DetailedCallback callback) {
        final Context app=c.getApplicationContext();
        final String request=action;
        MAIN.post(() -> {
            Diagnostics.event(app,"QUEUE_REQUEST","source=shared action="+request);
            if (!Protocol.COMMANDS.containsKey(request)) {
                if (callback!=null) callback.done(new Outcome(false,"failed","Unknown command",null));
                return;
            }
            Job job=new Job();
            job.context=app; job.action=request; job.callback=callback;
            job.stageCallback=stageCallback;
            job.noise=Prefs.isNoiseCommand(request);
            job.slider=isSliderCommand(request);
            job.id=++sequence;
            // Snapshot the latest mode request for all jobs. A later mode change
            // invalidates any old refresh, including reads after EQ/timer actions.
            job.noiseVersion=job.noise ? ++latestNoiseSequence : latestNoiseSequence;
            if (job.slider) {
                Iterator<Job> it=QUEUE.iterator();
                while (it.hasNext()) {
                    Job prior=it.next();
                    if (prior.slider && sliderFamily(prior.action).equals(sliderFamily(request))) {
                        it.remove();
                        Diagnostics.event(app,"QUEUE_REPLACED","old_job="+prior.id+" old_action="+prior.action
                                +" new_job="+job.id+" new_action="+request);
                        // Old pending slider requests never reach the headphones.
                        if (prior.callback!=null) prior.callback.done(new Outcome(false,"superseded",
                                "Replaced by newer slider level",null));
                    }
                }
            }
            QUEUE.add(job);
            Diagnostics.event(app,"QUEUE_ADD","job="+job.id+" action="+job.action+" noise_version="+job.noiseVersion
                    +" pending="+QUEUE.size());
            if (job.noise) feedbackIfNewest(job,"queued",null);
            startNext();
        });
    }
    public static void readState(Context c, Callback callback) {
        readStateDetailed(c,(success,message,state) -> {
            if (callback!=null) callback.done(success,message);
        });
    }
    public static void readStateDetailed(Context c, StateCallback callback) {
        final Context app=c.getApplicationContext();
        MAIN.post(() -> {
            Job job=new Job(); job.context=app; job.readOnly=true;
            job.stateCallback=callback; job.id=++sequence;
            job.noiseVersion=latestNoiseSequence;
            QUEUE.add(job);
            Diagnostics.event(app,"QUERY_ENQUEUE","job="+job.id+" manual_or_launch_read=true noise_version="+job.noiseVersion);
            startNext();
        });
    }
    private static boolean isSliderCommand(String command) {
        return command.startsWith("ANC ") || command.startsWith("AMBIENT ");
    }

    private static String sliderFamily(String command) {
        return command.startsWith("ANC ") ? "ANC" : "AMBIENT";
    }
    private static void startNext() {
        if (active) return;
        Job job=QUEUE.pollFirst();
        if (job==null) return;
        active=true;
        Diagnostics.event(job.context,"QUEUE_START","job="+job.id+" action="+(job.readOnly?"STATE_QUERY":job.action)
                +" remaining="+QUEUE.size()+" noise_version="+job.noiseVersion);
        if (job.noise) feedbackIfNewest(job,"sending",null);
        // ConnectedHeadphones.find uses Android's connected A2DP/Headset profiles;
        // it is asynchronous and has a bounded internal timeout.
        ConnectedHeadphones.find(job.context,(device,description) -> {
            Diagnostics.event(job.context,"DEVICE_CHECK","job="+job.id+" connected="+(device!=null)
                    +" detail="+(device!=null?"matched WH950NB":description));
            if (device==null) {
                end(job,new Outcome(false,"failed",description,null),null);
                return;
            }
            Prefs.mac(job.context,device.getAddress());
            WORKER.execute(() -> {
                Outcome outcome=null;
                Protocol.State state=null;
                String stateError=null;
                if (job.readOnly) {
                    try {
                        state=transfer(job.context,Protocol.fromHex(Protocol.STATE_QUERY),true,job.id,"manual_state_read");
                        outcome=new Outcome(true,"read",state.label(),state);
                    } catch (Exception e) {
                        outcome=new Outcome(false,"failed","Could not read headphone state",null);
                        stateError=e.toString();
                    }
                } else {
                    try {
                        // Keep the command and supported state query on one channel-18
                        // socket. Query-only fallback may reconnect after a short pause,
                        // but NEVER retries the control command itself.
                        SendResult tx=sendAndMaybeRead(job);
                        state=tx.state;
                        stateError=tx.queryError;
                        if (job.noise) {
                            // Do not publish an intermediate, different state as current.
                            // The transaction already attempted bounded convergence.
                            if (!Prefs.validNoiseState(state)) {
                                String detail="✗ Not confirmed: "+job.action;
                                outcome=new Outcome(true,"unverified",detail,null);
                            } else if ("OBSERVED".equals(state.confidence)) {
                                outcome=new Outcome(true,"verified","✓ Confirmed: "+job.action,state);
                            } else {
                                outcome=new Outcome(true,"reported","✓ Confirmed: "+job.action,state);
                            }
                        } else {
                            // A successful noise-mode query cannot prove that an EQ,
                            // sound-mode or sleep-timer command was actually applied.
                            outcome=new Outcome(true,"unverified","✓ Command sent — confirmation unavailable: "+job.action,state);
                        }
                    } catch (Exception e) {
                        outcome=new Outcome(false,"failed","✗ Not confirmed: "+job.action+" (not sent)",null);
                        stateError=e.toString();
                    }
                }
                if (stateError!=null) {
                    Log.w("EdifierControl","Bluetooth operation: "+stateError);
                    Diagnostics.event(job.context,"BT_ERROR","job="+job.id+" action="+(job.readOnly?"STATE_QUERY":job.action)
                            +" error="+stateError);
                }
                final Outcome result=outcome;
                final String logText=stateError;
                MAIN.post(() -> end(job,result,logText));
            });
        });
    }
    private static boolean matches(String action, Protocol.State state) {
        String mode= action.equals("ANC") || action.startsWith("ANC ") ? Protocol.ANC
                : action.equals("AMBIENT") || action.startsWith("AMBIENT ") ? Protocol.AMBIENT
                : action.equals("WIND") ? Protocol.WIND : Protocol.OFF;
        if (!mode.equals(state.mode)) return false;
        if (isSliderCommand(action)) {
            return action.substring(action.indexOf(' ')+1).equals(state.level);
        }
        return true;
    }
    private static void feedbackIfNewest(Job job, String stage, Outcome outcome) {
        if (!job.noise || job.noiseVersion!=latestNoiseSequence) return;
        Diagnostics.event(job.context,"NOTIFY_STATE","job="+job.id+" action="+job.action+" stage="+stage);
        Prefs.commandFeedback(job.context,job.action,stage);
        NotificationHelper.refreshIfEnabled(job.context);
        Tiles.refresh(job.context);
    }
    private static void end(Job job, Outcome outcome, String diagnostic) {
        Diagnostics.event(job.context,"QUEUE_RESULT","job="+job.id+" action="+(job.readOnly?"STATE_QUERY":job.action)
                +" sent="+outcome.sent+" verdict="+outcome.verdict+" mode="
                +(outcome.state==null?"none":outcome.state.mode)+" level="
                +(outcome.state==null?"none":outcome.state.level)+" confidence="
                +(outcome.state==null?"none":outcome.state.confidence));
        // Never deliver an older observed mode to a UI callback after a new
        // noise-mode command has been queued from another entry point.
        if (!job.readOnly && job.noiseVersion != latestNoiseSequence) {
            Diagnostics.event(job.context,"STALE_WRITE_RESULT","job="+job.id+" action="+job.action
                    +" newer_noise_version="+latestNoiseSequence);
            outcome = job.noise
                    ? new Outcome(outcome.sent,"superseded","Newer noise command pending",null)
                    : new Outcome(outcome.sent,outcome.verdict,outcome.message,null);
        }
        if (job.readOnly) {
            // A manual/automatic refresh can wait in the queue while a notification,
            // tile or slider requests a newer noise-control setting. Never publish
            // a snapshot taken before that newer request as the current mode.
            if (job.noiseVersion != latestNoiseSequence) {
                Diagnostics.event(job.context,"STALE_READ_IGNORED","job="+job.id+" newer_noise_version="+latestNoiseSequence);
                if (job.stateCallback != null)
                    job.stateCallback.done(false,"Refresh superseded by newer command",null);
            } else {
                if (outcome.sent && Prefs.validNoiseState(outcome.state)) {
                    Prefs.observedNoise(job.context,outcome.state);
                    String observedAction=outcome.state.mode + (outcome.state.level.isEmpty()?"":" "+outcome.state.level);
                    Prefs.commandFeedback(job.context,observedAction,"observed");
                    NotificationHelper.refreshIfEnabled(job.context);
                    Tiles.refresh(job.context);
                }
                if (job.stateCallback!=null)
                    job.stateCallback.done(outcome.sent,outcome.message,outcome.state);
            }
        } else {
            if (outcome.sent) {
                Prefs.lastRequested(job.context,job.action);
                if (job.noise && job.noiseVersion==latestNoiseSequence) {
                    Prefs.noiseRequested(job.context,job.action);
                    if (Prefs.validNoiseState(outcome.state)) Prefs.observedNoise(job.context,outcome.state);
                }
            }
            if (job.noise) {
                feedbackIfNewest(job,outcome.verdict,outcome);
            } else {
                // After non-noise commands, refresh the *noise-mode display*
                // only if no newer noise-control job superseded this result.
                if (outcome.sent && job.noiseVersion==latestNoiseSequence
                        && Prefs.validNoiseState(outcome.state)) {
                    Prefs.observedNoise(job.context,outcome.state);
                    String observed=outcome.state.mode
                            +(outcome.state.level.isEmpty()?"":" "+outcome.state.level);
                    Prefs.commandFeedback(job.context,observed,"observed");
                    NotificationHelper.refreshIfEnabled(job.context);
                }
                Tiles.refresh(job.context);
            }
            if (job.callback!=null) job.callback.done(outcome);
        }
        active=false;
        Diagnostics.event(job.context,"QUEUE_END","job="+job.id+" pending="+QUEUE.size());
        startNext();
    }

    /** Command write is complete even if state verification later fails. */
    private static final class SendResult {
        final Protocol.State state; // Only a matching readback is publishable.
        final Protocol.State lastMismatch; // Diagnostic only; never repaint the UI.
        final String queryError;
        SendResult(Protocol.State state,String queryError,Protocol.State lastMismatch) {
            this.state=state; this.queryError=queryError; this.lastMismatch=lastMismatch;
        }
    }

    private static final class VerifyResult {
        final Protocol.State matched;
        final Protocol.State lastMismatch;
        VerifyResult(Protocol.State matched,Protocol.State lastMismatch) {
            this.matched=matched; this.lastMismatch=lastMismatch;
        }
    }

    /** Wait briefly for state convergence without repeating the control command.
     *  All retries are CC state queries on the original connected socket.
     *  A previous-state response is logged but never treated as the new setting.
     */
    private static VerifyResult verifyConverged(Job job, InputStream input, OutputStream output)
            throws Exception {
        final Context c=job.context;
        final long deadline=System.nanoTime()+3_500_000_000L;
        final int maxQueries=4;
        final byte[] query=Protocol.fromHex(Protocol.STATE_QUERY);
        Protocol.State lastMismatch=null;
        for (int attempt=1; attempt<=maxQueries; attempt++) {
            long remaining=(deadline-System.nanoTime())/1_000_000L;
            if (remaining<=60) break;
            if (attempt>1) {
                long pause=Math.min(700L,Math.max(0L,remaining-60L));
                if (pause>0) Thread.sleep(pause);
                remaining=(deadline-System.nanoTime())/1_000_000L;
                if (remaining<=60) break;
            }
            Diagnostics.event(c,"VERIFY_QUERY_ATTEMPT","job="+job.id+" attempt="+attempt
                    +" max="+maxQueries+" remaining_ms="+remaining);
            output.write(query);
            output.flush();
            Diagnostics.event(c,"BT_TX","job="+job.id+" phase=same_socket_state_read attempt="
                    +attempt+" length="+query.length+" hex="+Diagnostics.hex(query,0,query.length));
            try {
                long readWindow=Math.min(1100L,Math.max(60L,remaining));
                Protocol.State observed=readStateReply(c,input,job.id,"same_socket_state_read",readWindow);
                if (matches(job.action,observed)) {
                    Diagnostics.event(c,"VERIFY_MATCH","job="+job.id+" attempt="+attempt
                            +" mode="+observed.mode+" level="+observed.level
                            +" confidence="+observed.confidence);
                    return new VerifyResult(observed,lastMismatch);
                }
                lastMismatch=observed;
                Diagnostics.event(c,"VERIFY_MISMATCH","job="+job.id+" attempt="+attempt
                        +" wanted="+job.action+" observed="+observed.label());
            } catch (java.io.IOException error) {
                // A bounded read may produce no complete CC frame. Try another
                // query until the deadline; actual I/O faults still fail to fallback.
                if (!String.valueOf(error.getMessage()).startsWith("No valid state reply within")) throw error;
                Diagnostics.event(c,"VERIFY_NO_REPLY","job="+job.id+" attempt="+attempt);
            }
        }
        Diagnostics.event(c,"VERIFY_EXHAUSTED","job="+job.id+" wanted="+job.action
                +" last="+(lastMismatch==null?"none":lastMismatch.label()));
        return new VerifyResult(null,lastMismatch);
    }

    /** The same socket is used for command + query to avoid an immediate RFCOMM reconnect. */
    private static SendResult sendAndMaybeRead(Job job) throws Exception {
        Context c=job.context;
        if (!permitted(c)) throw new SecurityException("Grant Nearby devices permission in the app");
        byte[] command=Protocol.bytes(job.action); // Command bytes and checksum are unchanged.
        BluetoothSocket socket=makeChannel18Socket(c);
        Thread watchdog=startWatchdog(socket,8500);
        Protocol.State reply=null;
        Protocol.State lastMismatch=null;
        String sameSocketFailure=null;
        boolean sent=false;
        try {
            Diagnostics.event(c,"BT_BEGIN","job="+job.id+" phase=write_and_optional_query kind=write bytes="
                    +command.length+" tx="+Diagnostics.hex(command,0,command.length));
            long start=System.nanoTime();
            socket.connect();
            Diagnostics.event(c,"BT_CONNECTED","job="+job.id+" phase=write_and_optional_query channel=18 ms="
                    +((System.nanoTime()-start)/1_000_000L));
            OutputStream output=socket.getOutputStream();
            output.write(command);
            output.flush();
            Diagnostics.event(c,"BT_TX","job="+job.id+" phase=write_command length="+command.length
                    +" hex="+Diagnostics.hex(command,0,command.length));
            // Retain the existing Tasker-tested settling period.
            Thread.sleep(200);
            sent=true;
            Diagnostics.event(c,"BT_WRITE_COMPLETE","job="+job.id);
            if (job.noise) {
                MAIN.post(() -> {
                    Diagnostics.event(c,"QUERY_STAGE","job="+job.id+" action="+job.action
                            +" checking_after_send=true path=same_socket");
                    if (job.stageCallback!=null) job.stageCallback.changed("checking");
                    feedbackIfNewest(job,"checking",null);
                });
                try {
                    Diagnostics.event(c,"BT_REUSE_QUERY_BEGIN","job="+job.id+" channel=18");
                    VerifyResult checked=verifyConverged(job,socket.getInputStream(),output);
                    reply=checked.matched;
                    lastMismatch=checked.lastMismatch;
                    Diagnostics.event(c,"BT_REUSE_QUERY_RESULT","job="+job.id
                            +" matched="+(reply!=null)+" last_mismatch="
                            +(lastMismatch==null?"none":lastMismatch.label()));
                } catch (Exception readError) {
                    sameSocketFailure=readError.toString();
                    Diagnostics.event(c,"BT_REUSE_QUERY_RESULT","job="+job.id
                            +" success=false error="+sameSocketFailure);
                }
            }
        } finally {
            watchdog.interrupt();
            try { socket.close(); } catch (Exception ignored) {}
            Diagnostics.event(c,"BT_CLOSE","job="+job.id+" phase=write_and_optional_query");
        }
        // A valid but unchanged headphone state across every verification read
        // means the control command was not confirmed. One additional write on a
        // FRESH connection is permitted for idempotent noise-setting commands.
        // Never retry upon transport/read failures, or after a newer noise request.
        if (job.noise && sent && reply==null && lastMismatch!=null) {
            if (job.noiseVersion!=latestNoiseSequence) {
                Diagnostics.event(c,"COMMAND_RETRY_SKIPPED","job="+job.id+" reason=newer_noise_request");
                return new SendResult(null,null,lastMismatch);
            }
            final int cooldownMs=1500;
            Diagnostics.event(c,"COMMAND_RETRY_WAIT","job="+job.id
                    +" after=verify_exhausted delay_ms="+cooldownMs);
            try {
                Thread.sleep(cooldownMs);
                if (job.noiseVersion!=latestNoiseSequence) {
                    Diagnostics.event(c,"COMMAND_RETRY_SKIPPED","job="+job.id+" reason=newer_noise_request");
                    return new SendResult(null,null,lastMismatch);
                }
                SendResult second=retryNoiseOnFreshSocket(job);
                Diagnostics.event(c,"COMMAND_RETRY_RESULT","job="+job.id+" matched="+(second.state!=null)
                        +" last="+(second.lastMismatch==null?"none":second.lastMismatch.label()));
                return new SendResult(second.state,second.queryError,
                        second.lastMismatch==null?lastMismatch:second.lastMismatch);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                Diagnostics.event(c,"COMMAND_RETRY_STOP","job="+job.id+" reason=interrupted");
                return new SendResult(null,"Retry interrupted",lastMismatch);
            } catch (Exception retryFailure) {
                Diagnostics.event(c,"COMMAND_RETRY_STOP","job="+job.id+" error="+retryFailure);
                return new SendResult(null,retryFailure.toString(),lastMismatch);
            }
        }
        // EQ, sound modes and timers have no verified state query. No extra connection.
        if (!job.noise || reply!=null || lastMismatch!=null)
            return new SendResult(reply,null,lastMismatch);
        if (!sent) throw new java.io.IOException("Control command not transmitted");

        // Fallback is a QUERY ONLY: no blind repetition of the control command.
        // Delay is bounded; this is a hypothesis to test against the 5-second manual result.
        final int settleMs=1500;
        Diagnostics.event(c,"BT_FALLBACK_WAIT","job="+job.id+" delay_ms="+settleMs);
        try { Thread.sleep(settleMs); }
        catch (InterruptedException e) { Thread.currentThread().interrupt();
            return new SendResult(null,"Fallback interrupted; primary="+sameSocketFailure,null); }
        try {
            Diagnostics.event(c,"BT_FALLBACK_QUERY_BEGIN","job="+job.id+" query_only=true");
            Protocol.State recovered=transfer(c,Protocol.fromHex(Protocol.STATE_QUERY),true,
                    job.id,"fallback_state_read");
            boolean matched=Prefs.validNoiseState(recovered) && matches(job.action,recovered);
            Diagnostics.event(c,"BT_FALLBACK_QUERY_RESULT","job="+job.id+" success=true matched="+matched);
            return new SendResult(matched?recovered:null,null,matched?null:recovered);
        } catch (Exception fallbackError) {
            Diagnostics.event(c,"BT_FALLBACK_QUERY_RESULT","job="+job.id+" success=false error="+fallbackError);
            return new SendResult(null,"Same socket: "+sameSocketFailure
                    +"; delayed query: "+fallbackError,null);
        }
    }

    /** One controlled retry for a noise command after a confirmed different state.
     *  This never runs for EQ, sound modes or timers, and never repeats a third time.
     */
    private static SendResult retryNoiseOnFreshSocket(Job job) throws Exception {
        Context c=job.context;
        if (job.noiseVersion!=latestNoiseSequence) {
            Diagnostics.event(c,"COMMAND_RETRY_SKIPPED","job="+job.id+" reason=newer_noise_request");
            return new SendResult(null,null,null);
        }
        byte[] command=Protocol.bytes(job.action);
        BluetoothSocket socket=makeChannel18Socket(c);
        Thread watchdog=startWatchdog(socket,8500);
        try {
            Diagnostics.event(c,"COMMAND_RETRY_BEGIN","job="+job.id+" action="+job.action
                    +" max_writes=2 connection=new");
            long begin=System.nanoTime();
            socket.connect();
            Diagnostics.event(c,"BT_CONNECTED","job="+job.id+" phase=command_retry channel=18 ms="
                    +((System.nanoTime()-begin)/1_000_000L));
            if (job.noiseVersion!=latestNoiseSequence) {
                Diagnostics.event(c,"COMMAND_RETRY_SKIPPED","job="+job.id+" reason=newer_noise_request");
                return new SendResult(null,null,null);
            }
            OutputStream output=socket.getOutputStream();
            output.write(command);
            output.flush();
            Diagnostics.event(c,"BT_TX","job="+job.id+" phase=command_retry length="+command.length
                    +" hex="+Diagnostics.hex(command,0,command.length));
            Thread.sleep(200);
            Diagnostics.event(c,"COMMAND_RETRY_SENT","job="+job.id+" write_count=2");
            VerifyResult checked=verifyConverged(job,socket.getInputStream(),output);
            return new SendResult(checked.matched,null,checked.lastMismatch);
        } finally {
            watchdog.interrupt();
            try { socket.close(); } catch (Exception ignored) {}
            Diagnostics.event(c,"BT_CLOSE","job="+job.id+" phase=command_retry");
        }
    }

    private static BluetoothSocket makeChannel18Socket(Context c) throws Exception {
        if (!permitted(c)) throw new SecurityException("Grant Nearby devices permission in the app");
        String address=Prefs.mac(c);
        if (address.length()==0) throw new IllegalStateException("Connect WH950NB first");
        BluetoothManager manager=(BluetoothManager)c.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if (adapter==null || !adapter.isEnabled()) throw new IllegalStateException("Bluetooth is off");
        BluetoothDevice device=adapter.getRemoteDevice(address);
        Method creator=device.getClass().getMethod("createRfcommSocket",Integer.TYPE);
        return (BluetoothSocket)creator.invoke(device,18);
    }

    private static Thread startWatchdog(BluetoothSocket socket,long millis) {
        Thread watchdog=new Thread(() -> {
            try { Thread.sleep(millis); socket.close(); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            catch (Exception ignored) {}
        },"edifier-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        return watchdog;
    }

    /** The query decoder is unchanged; log packets on either read path. */
    private static Protocol.State readStateReply(Context c,InputStream in,long jobId,String phase)
            throws Exception {
        return readStateReply(c,in,jobId,phase,3000L);
    }
    private static Protocol.State readStateReply(Context c,InputStream in,long jobId,String phase,
                                                long timeoutMs) throws Exception {
        byte[] rx=new byte[256]; int count=0;
        long deadline=System.nanoTime()+timeoutMs*1_000_000L;
        while (System.nanoTime()<deadline) {
            int available=in.available();
            if (available<=0) { Thread.sleep(25); continue; }
            if (count>192) { System.arraycopy(rx,count-32,rx,0,32); count=32; }
            int n=in.read(rx,count,Math.min(available,rx.length-count));
            if (n<0) {
                Diagnostics.event(c,"BT_RX_EOF","job="+jobId+" phase="+phase+" bytes_total="+count);
                break;
            }
            if (n==0) continue;
            count+=n;
            Diagnostics.event(c,"BT_RX_CHUNK","job="+jobId+" phase="+phase+" n="+n+" total="+count
                    +" hex="+Diagnostics.hex(rx,count-n,n));
            Protocol.State state=Protocol.parseState(rx,count);
            if (state!=null) {
                Diagnostics.event(c,"BT_STATE_DECODED","job="+jobId+" phase="+phase+" mode="+state.mode
                        +" level="+state.level+" confidence="+state.confidence+" frame="+state.raw);
                return state;
            }
        }
        Diagnostics.event(c,"BT_RX_UNRECOGNIZED","job="+jobId+" phase="+phase+" bytes_total="+count
                +" raw="+Diagnostics.hex(rx,0,count));
        throw new java.io.IOException("No valid state reply within "+timeoutMs+" ms");
    }

    /** A standalone read for manual refresh, also used by delayed fallback. */
    private static Protocol.State transfer(Context c,byte[] packet,boolean readState,
                                            long jobId,String phase) throws Exception {
        if (!readState) throw new IllegalArgumentException("Standalone transfer supports state query only");
        BluetoothSocket socket=makeChannel18Socket(c);
        Thread watchdog=startWatchdog(socket,6500);
        try {
            Diagnostics.event(c,"BT_BEGIN","job="+jobId+" phase="+phase+" kind=query bytes="
                    +packet.length+" tx="+Diagnostics.hex(packet,0,packet.length));
            long start=System.nanoTime();
            socket.connect();
            Diagnostics.event(c,"BT_CONNECTED","job="+jobId+" phase="+phase+" channel=18 ms="
                    +((System.nanoTime()-start)/1_000_000L));
            OutputStream output=socket.getOutputStream();
            output.write(packet);
            output.flush();
            Diagnostics.event(c,"BT_TX","job="+jobId+" phase="+phase+" length="+packet.length
                    +" hex="+Diagnostics.hex(packet,0,packet.length));
            return readStateReply(c,socket.getInputStream(),jobId,phase);
        } finally {
            watchdog.interrupt();
            try { socket.close(); } catch (Exception ignored) {}
            Diagnostics.event(c,"BT_CLOSE","job="+jobId+" phase="+phase);
        }
    }
}
