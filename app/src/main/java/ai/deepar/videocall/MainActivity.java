package ai.deepar.videocall;

import android.Manifest;
import android.graphics.Bitmap;
import android.media.Image;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ai.deepar.ar.ARErrorType;
import ai.deepar.ar.AREventListener;
import ai.deepar.ar.DeepAR;
import io.agora.rtc.Constants;
import io.agora.rtc.IRtcEngineEventHandler;
import io.agora.rtc.RtcEngine;
import io.agora.rtc.video.VideoCanvas;
import io.agora.rtc.video.VideoEncoderConfiguration;

public class MainActivity extends PermissionsActivity implements AREventListener {

    private static final String TAG = "MainActivity";
    private CameraGrabber cameraGrabber;
    private DeepAR deepAR;
    private GLSurfaceView surfaceView;
    private DeepARRenderer renderer;
    private RtcEngine mRtcEngine;
    private boolean callInProgress;

    private RecyclerView remoteViewContainer;
    private VideoAdapter videoAdapter;
    private final List<ViewData> viewDataList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        deepAR = new DeepAR(this);
        deepAR.setLicenseKey("7c17ba03d0cd2397334a3fade787b42ac13265bd4a325f0778553649399b28dfa5f9db02080c2d1c");
        deepAR.initialize(this, this);
        setContentView(R.layout.activity_main);
        callInProgress = false;
        remoteViewContainer = findViewById(R.id.rcv);
    }

    @Override
    protected void onStart() {
        super.onStart();

        checkMultiplePermissions(
                Arrays.asList(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH),
                "The app needs camera, external storage and record audio permissions",
                100,
                new PermissionsActivity.MultiplePermissionsCallback() {
                    @Override
                    public void onAllPermissionsGranted() {
                        setup();
                    }

                    @Override
                    public void onPermissionsDenied(List<String> deniedPermissions) {
                        Log.d("MainActivity", "Permissions Denied!");
                    }
                });
    }

    void setup() {
        cameraGrabber = new CameraGrabber();
        cameraGrabber.initCamera(new CameraGrabberListener() {
            @Override
            public void onCameraInitialized() {
                cameraGrabber.setFrameReceiver(deepAR);
                cameraGrabber.startPreview();
            }

            @Override
            public void onCameraError(String errorMsg) {
                Log.e("Error", errorMsg);
            }
        });

        initializeEngine();
        setupVideoConfig();

        surfaceView = new GLSurfaceView(this);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        renderer = new DeepARRenderer(deepAR, mRtcEngine);

        surfaceView.setEGLContextFactory(new DeepARRenderer.MyContextFactory(renderer));

        surfaceView.setRenderer(renderer);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        // add SurfaceView of Local Video to Adapter
        // and submitList to notify RecyclerView to re-draw
        viewDataList.add(new ViewData(surfaceView));
        videoAdapter = new VideoAdapter();
        remoteViewContainer.setAdapter(videoAdapter);
        videoAdapter.submitList(new ArrayList<>(viewDataList));

        final Button btn = findViewById(R.id.startCall);
        mRtcEngine.setExternalVideoSource(true, true, true);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callInProgress) {
                    callInProgress = false;
                    renderer.setCallInProgress(false);
                    mRtcEngine.leaveChannel();
                    onRemoteUserLeft();
                    btn.setText("Start the call");
                } else {
                    callInProgress = true;
                    joinChannel();
                    btn.setText("End the call");
                }
                // Simulate video adapter need add and remove if participant join and leave call
                // In my case, I have up to 4 video previews
                // If only one local video: the height of item_video will match_parent
                // |------------------|
                // |                  |
                // |                  |
                // |                  |
                // |                  |
                // |                  |
                // |                  |
                // |                  |
                // |__________________|
                // If there are 2 persons: the height of item_video will be a half of parent
                // |------------------|
                // |                  |
                // |                  |
                // |                  |
                // |------------------|
                // |                  |
                // |                  |
                // |                  |
                // |__________________|
                // So I must call notifyItemChanged to re-calculate the height itemView
                videoAdapter.notifyItemChanged(0);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surfaceView != null) {
            surfaceView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (surfaceView != null) {
            surfaceView.onPause();
        }
    }

    @Override
    protected void onStop() {
        cameraGrabber.setFrameReceiver(null);
        cameraGrabber.stopPreview();
        cameraGrabber.releaseCamera();
        cameraGrabber = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // I try to clear all the recyclerView item_video
        viewDataList.clear();
        videoAdapter.submitList(new ArrayList<>(viewDataList));
        remoteViewContainer.removeAllViews();
        super.onDestroy();
        // but sometime it still fail to call deepAR.release(), when someone join call and leave call several times
        deepAR.release();
        mRtcEngine.leaveChannel();
        RtcEngine.destroy();
    }

    private final IRtcEngineEventHandler mRtcEventHandler = new IRtcEngineEventHandler() {
        @Override
        public void onWarning(int warn) {
            Log.e(TAG, "warning: " + warn);
        }

        @Override
        public void onError(int err) {
            Log.e(TAG, "error: " + err);
        }

        @Override
        public void onJoinChannelSuccess(String channel, final int uid, int elapsed) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Log.e(TAG, "onJoinChannelSuccess");
                    renderer.setCallInProgress(true);
                }
            });
        }

        @Override
        public void onFirstRemoteVideoDecoded(final int uid, int width, int height, int elapsed) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Log.e(TAG, "onFirstRemoteVideoDecoded");
                    setupRemoteVideo(uid);
                }
            });
        }

        @Override
        public void onUserOffline(final int uid, int reason) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Log.e(TAG, "onUserOffline");
                    onRemoteUserLeft();
                }
            });
        }
    };

    private void initializeEngine() {
        try {
            mRtcEngine = RtcEngine.create(getBaseContext(), "91ba75957b7648ce8287e0d23baa41a8", mRtcEventHandler);
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
            throw new RuntimeException("NEED TO check rtc sdk init fatal error\n" + Log.getStackTraceString(e));
        }
        mRtcEngine.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING);
    }

    private void setupVideoConfig() {
        mRtcEngine.enableVideo();

        mRtcEngine.setExternalVideoSource(true, true, true);

        // Please go to this page for detailed explanation
        // https://docs.agora.io/en/Video/API%20Reference/java/classio_1_1agora_1_1rtc_1_1_rtc_engine.html#af5f4de754e2c1f493096641c5c5c1d8f
        mRtcEngine.setVideoEncoderConfiguration(new VideoEncoderConfiguration(
                // Agora seems to work best with "Square" resolutions (Aspect Ratio 1:1)
                // At least when used in combination with DeepAR
                VideoEncoderConfiguration.VD_480x480,
                VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                VideoEncoderConfiguration.STANDARD_BITRATE,
                VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT));


    }


    private void joinChannel() {
        mRtcEngine.setClientRole(Constants.CLIENT_ROLE_BROADCASTER);
        mRtcEngine.joinChannel(null, "channel", "Extra Optional Data", 0);
    }

    private void setupRemoteVideo(int uid) {
        SurfaceView surfaceView = RtcEngine.CreateRendererView(getBaseContext());
        mRtcEngine.setupRemoteVideo(new VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, uid));
        viewDataList.add(new ViewData(surfaceView));
        videoAdapter.submitList(new ArrayList<>(viewDataList));
    }

    private void onRemoteUserLeft() {
        if (viewDataList.size() > 1) {
            viewDataList.remove(1);
            videoAdapter.submitList(new ArrayList<>(viewDataList));
        }
    }

    @Override
    public void screenshotTaken(Bitmap bitmap) {

    }

    @Override
    public void videoRecordingStarted() {

    }

    @Override
    public void videoRecordingFinished() {

    }

    @Override
    public void videoRecordingFailed() {

    }

    @Override
    public void videoRecordingPrepared() {

    }

    @Override
    public void shutdownFinished() {

    }

    @Override
    public void initialized() {
        deepAR.switchEffect("mask", "file:///android_asset/aviators");
    }

    @Override
    public void faceVisibilityChanged(boolean b) {

    }

    @Override
    public void imageVisibilityChanged(String s, boolean b) {

    }

    @Override
    public void frameAvailable(Image image) {

    }

    @Override
    public void error(ARErrorType arErrorType, String s) {

    }

    @Override
    public void effectSwitched(String s) {

    }
}
