package org.szres.glyphlens.test;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.io.File;
import java.io.IOException;

// Framework-only: this test APK process cannot use dependencies packaged only in the target APK.
public final class FixtureVideoActivity extends Activity implements TextureView.SurfaceTextureListener {
    public static final String ACTION_STOP = "org.szres.glyphlens.test.STOP_VIDEO";

    private MediaPlayer player;
    private String source;
    private TextureView textureView;
    private int videoWidth;
    private int videoHeight;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ACTION_STOP.equals(getIntent().getAction())) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        source = getIntent().getStringExtra("video");
        if (source == null) {
            source = new File(getExternalFilesDir(null), "real-recording.mp4").getAbsolutePath();
        }
        textureView = new TextureView(this);
        textureView.setOpaque(false);
        textureView.setSurfaceTextureListener(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(textureView);
        setContentView(root);
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_STOP.equals(intent.getAction())) {
            releasePlayer();
            finish();
            return;
        }
        String nextSource = intent.getStringExtra("video");
        if (nextSource != null) source = nextSource;
        if (player != null) {
            player.seekTo(0);
            player.start();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (player != null) {
            player.setPlaybackParams(player.getPlaybackParams().setSpeed(1.0f));
            player.seekTo(0);
            player.start();
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        player = new MediaPlayer();
        Surface surface = new Surface(texture);
        player.setSurface(surface);
        surface.release();
        player.setOnVideoSizeChangedListener((mediaPlayer, newWidth, newHeight) -> {
            videoWidth = newWidth;
            videoHeight = newHeight;
            fitVideoInSurface();
        });
        player.setOnPreparedListener(mediaPlayer -> {
            mediaPlayer.setLooping(true);
            mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(1.0f));
            mediaPlayer.seekTo(0);
            mediaPlayer.start();
        });
        player.setOnErrorListener((mediaPlayer, what, extra) -> {
            finish();
            return true;
        });
        try {
            player.setDataSource(source);
            player.prepareAsync();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot play fixture: " + source, failure);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        fitVideoInSurface();
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        releasePlayer();
        return true;
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        super.onDestroy();
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void fitVideoInSurface() {
        float width = textureView.getWidth();
        float height = textureView.getHeight();
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return;
        float scale = Math.min(width / videoWidth, height / videoHeight);
        Matrix transform = new Matrix();
        transform.setScale(scale * videoWidth / width, scale * videoHeight / height, width / 2, height / 2);
        textureView.setTransform(transform);
    }
}
