/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;

import androidx.annotation.RequiresApi;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;

import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.messenger.video.MediaCodecVideoConvertor;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Adapters.FiltersView;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.EmbedBottomSheet;
import org.telegram.ui.Components.PhotoFilterView;
import org.telegram.ui.Components.PipRoundVideoView;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.PhotoViewer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;

import tw.nekomimi.nekogram.SaveToDownloadReceiver;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.NekoXConfig;

public class MediaController implements AudioManager.OnAudioFocusChangeListener, NotificationCenter.NotificationCenterDelegate, SensorEventListener {

    private native int startRecord(String path, int sampleRate);

    private native int writeFrame(ByteBuffer frame, int len);

    private native void stopRecord();

    public static native int isOpusFile(String path);

    public native byte[] getWaveform(String path);

    public native byte[] getWaveform2(short[] array, int length);

    public boolean isBuffering() {
        if (audioPlayer != null) {
            return audioPlayer.isBuffering();
        }
        return false;
    }

    private static class AudioBuffer {
        public AudioBuffer(int capacity) {
            buffer = ByteBuffer.allocateDirect(capacity);
            bufferBytes = new byte[capacity];
        }

        ByteBuffer buffer;
        byte[] bufferBytes;
        int size;
        int finished;
        long pcmOffset;
    }

    private static final String[] projectionPhotos = {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            Build.VERSION.SDK_INT > 28 ? MediaStore.Images.Media.DATE_MODIFIED : MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE
    };

    private static final String[] projectionVideo = {
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            Build.VERSION.SDK_INT > 28 ? MediaStore.Images.Media.DATE_MODIFIED : MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.SIZE
    };

    public static class AudioEntry {
        public long id;
        public String author;
        public String title;
        public String genre;
        public int duration;
        public String path;
        public MessageObject messageObject;
    }

    public static class AlbumEntry {
        public int bucketId;
        public boolean videoOnly;
        public String bucketName;
        public PhotoEntry coverPhoto;
        public ArrayList<PhotoEntry> photos = new ArrayList<>();
        public SparseArray<PhotoEntry> photosByIds = new SparseArray<>();

        public AlbumEntry(int bucketId, String bucketName, PhotoEntry coverPhoto) {
            this.bucketId = bucketId;
            this.bucketName = bucketName;
            this.coverPhoto = coverPhoto;
        }

        public void addPhoto(PhotoEntry photoEntry) {
            photos.add(photoEntry);
            photosByIds.put(photoEntry.imageId, photoEntry);
        }
    }

    public static class SavedFilterState {
        public float enhanceValue;
        public float softenSkinValue;
        public float exposureValue;
        public float contrastValue;
        public float warmthValue;
        public float saturationValue;
        public float fadeValue;
        public int tintShadowsColor;
        public int tintHighlightsColor;
        public float highlightsValue;
        public float shadowsValue;
        public float vignetteValue;
        public float grainValue;
        public int blurType;
        public float sharpenValue;
        public PhotoFilterView.CurvesToolValue curvesToolValue = new PhotoFilterView.CurvesToolValue();
        public float blurExcludeSize;
        public org.telegram.ui.Components.Point blurExcludePoint;
        public float blurExcludeBlurSize;
        public float blurAngle;
    }

    public static class CropState {
        public float cropPx;
        public float cropPy;
        public float cropScale = 1;
        public float cropRotate;
        public float cropPw = 1;
        public float cropPh = 1;
        public int transformWidth;
        public int transformHeight;
        public int transformRotation;
        public boolean mirrored;

        public float stateScale;
        public float scale;
        public Matrix matrix;
        public int width;
        public int height;
        public boolean freeform;
        public float lockedAspectRatio;

        public boolean initied;

        @Override
        public CropState clone() {
            CropState cloned = new CropState();

            cloned.cropPx = this.cropPx;
            cloned.cropPy = this.cropPy;
            cloned.cropScale = this.cropScale;
            cloned.cropRotate = this.cropRotate;
            cloned.cropPw = this.cropPw;
            cloned.cropPh = this.cropPh;
            cloned.transformWidth = this.transformWidth;
            cloned.transformHeight = this.transformHeight;
            cloned.transformRotation = this.transformRotation;
            cloned.mirrored = this.mirrored;

            cloned.stateScale = this.stateScale;
            cloned.scale = this.scale;
            cloned.matrix = this.matrix;
            cloned.width = this.width;
            cloned.height = this.height;
            cloned.freeform = this.freeform;
            cloned.lockedAspectRatio = this.lockedAspectRatio;

            cloned.initied = this.initied;
            return cloned;
        }
    }

    public static class MediaEditState {

        public CharSequence caption;

        public String thumbPath;
        public String imagePath;
        public String filterPath;
        public String paintPath;
        public String croppedPaintPath;
        public String fullPaintPath;

        public ArrayList<TLRPC.MessageEntity> entities;
        public SavedFilterState savedFilterState;
        public ArrayList<VideoEditedInfo.MediaEntity> mediaEntities;
        public ArrayList<VideoEditedInfo.MediaEntity> croppedMediaEntities;
        public ArrayList<TLRPC.InputDocument> stickers;
        public VideoEditedInfo editedInfo;
        public long averageDuration;
        public boolean isFiltered;
        public boolean isPainted;
        public boolean isCropped;
        public int ttl;

        public CropState cropState;

        public String getPath() {
            return null;
        }

        public void reset() {
            caption = null;
            thumbPath = null;
            filterPath = null;
            imagePath = null;
            paintPath = null;
            croppedPaintPath = null;
            isFiltered = false;
            isPainted = false;
            isCropped = false;
            ttl = 0;
            mediaEntities = null;
            editedInfo = null;
            entities = null;
            savedFilterState = null;
            stickers = null;
            cropState = null;
        }

        public void copyFrom(MediaEditState state) {
            caption = state.caption;

            thumbPath = state.thumbPath;
            imagePath = state.imagePath;
            filterPath = state.filterPath;
            paintPath = state.paintPath;
            croppedPaintPath = state.croppedPaintPath;
            fullPaintPath = state.fullPaintPath;

            entities = state.entities;
            savedFilterState = state.savedFilterState;
            mediaEntities = state.mediaEntities;
            croppedMediaEntities = state.croppedMediaEntities;
            stickers = state.stickers;
            editedInfo = state.editedInfo;
            averageDuration = state.averageDuration;
            isFiltered = state.isFiltered;
            isPainted = state.isPainted;
            isCropped = state.isCropped;
            ttl = state.ttl;

            cropState = state.cropState;
        }
    }

    public static class PhotoEntry extends MediaEditState {
        public int bucketId;
        public int imageId;
        public long dateTaken;
        public int duration;
        public int width;
        public int height;
        public long size;
        public String path;
        public int orientation;
        public boolean isVideo;
        public boolean isMuted;
        public boolean canDeleteAfter;
        public boolean hasSpoiler;

        public boolean isChatPreviewSpoilerRevealed;
        public boolean isAttachSpoilerRevealed;

        public PhotoEntry(int bucketId, int imageId, long dateTaken, String path, int orientation, boolean isVideo, int width, int height, long size) {
            this.bucketId = bucketId;
            this.imageId = imageId;
            this.dateTaken = dateTaken;
            this.path = path;
            this.width = width;
            this.height = height;
            this.size = size;
            if (isVideo) {
                this.duration = orientation;
            } else {
                this.orientation = orientation;
            }
            this.isVideo = isVideo;
        }

        @Override
        public void copyFrom(MediaEditState state) {
            super.copyFrom(state);
            this.hasSpoiler = state instanceof PhotoEntry && ((PhotoEntry) state).hasSpoiler;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public void reset() {
            if (isVideo) {
                if (filterPath != null) {
                    new File(filterPath).delete();
                    filterPath = null;
                }
            }
            hasSpoiler = false;
            super.reset();
        }
    }

    public static class SearchImage extends MediaEditState {
        public String id;
        public String imageUrl;
        public String thumbUrl;
        public int width;
        public int height;
        public int size;
        public int type;
        public int date;
        public CharSequence caption;
        public TLRPC.Document document;
        public TLRPC.Photo photo;
        public TLRPC.PhotoSize photoSize;
        public TLRPC.PhotoSize thumbPhotoSize;
        public TLRPC.BotInlineResult inlineResult;
        public HashMap<String, String> params;

        @Override
        public String getPath() {
            if (photoSize != null) {
                return FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(photoSize, true).getAbsolutePath();
            } else if (document != null) {
                return FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(document, true).getAbsolutePath();
            } else {
                return ImageLoader.getHttpFilePath(imageUrl, "jpg").getAbsolutePath();
            }
        }

        @Override
        public void reset() {
            super.reset();
        }

        public String getAttachName() {
            if (photoSize != null) {
                return FileLoader.getAttachFileName(photoSize);
            } else if (document != null) {
                return FileLoader.getAttachFileName(document);
            }
            return Utilities.MD5(imageUrl) + "." + ImageLoader.getHttpUrlExtension(imageUrl, "jpg");
        }

        public String getPathToAttach() {
            if (photoSize != null) {
                return FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(photoSize, true).getAbsolutePath();
            } else if (document != null) {
                return FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(document, true).getAbsolutePath();
            } else {
                return imageUrl;
            }
        }
    }

    AudioManager.OnAudioFocusChangeListener audioRecordFocusChangedListener = focusChange -> {
        if (focusChange != AudioManager.AUDIOFOCUS_GAIN) {
            hasRecordAudioFocus = false;
        }
    };

    public final static int VIDEO_BITRATE_1080 = 6800_000;
    public final static int VIDEO_BITRATE_720 = 2621_440;
    public final static int VIDEO_BITRATE_480 = 1000_000;
    public final static int VIDEO_BITRATE_360 = 750_000;

    public final static String VIDEO_MIME_TYPE = "video/avc";
    public final static String AUIDO_MIME_TYPE = "audio/mp4a-latm";

    private final Object videoConvertSync = new Object();

    private SensorManager sensorManager;
    private boolean ignoreProximity;
    private PowerManager.WakeLock proximityWakeLock;
    private Sensor proximitySensor;
    private Sensor accelerometerSensor;
    private Sensor linearSensor;
    private Sensor gravitySensor;
    private boolean raiseToEarRecord;
    private ChatActivity raiseChat;
    private boolean accelerometerVertical;
    private int raisedToTop;
    private int raisedToTopSign;
    private int raisedToBack;
    private int countLess;
    private long timeSinceRaise;
    private long lastTimestamp = 0;
    private boolean proximityTouched;
    private boolean proximityHasDifferentValues;
    private float lastProximityValue = -100;
    private boolean useFrontSpeaker;
    private boolean inputFieldHasText;
    private boolean allowStartRecord;
    private boolean ignoreOnPause;
    private boolean sensorsStarted;
    private float previousAccValue;
    private float[] gravity = new float[3];
    private float[] gravityFast = new float[3];
    private float[] linearAcceleration = new float[3];

    private int hasAudioFocus;
    private boolean hasRecordAudioFocus;
    private boolean callInProgress;
    private int audioFocus = AUDIO_NO_FOCUS_NO_DUCK;
    private boolean resumeAudioOnFocusGain;

    private static final float VOLUME_DUCK = 0.2f;
    private static final float VOLUME_NORMAL = 1.0f;
    private static final int AUDIO_NO_FOCUS_NO_DUCK = 0;
    private static final int AUDIO_NO_FOCUS_CAN_DUCK = 1;
    private static final int AUDIO_FOCUSED = 2;

    private static class VideoConvertMessage {
        public MessageObject messageObject;
        public VideoEditedInfo videoEditedInfo;
        public int currentAccount;

        public VideoConvertMessage(MessageObject object, VideoEditedInfo info) {
            messageObject = object;
            currentAccount = messageObject.currentAccount;
            videoEditedInfo = info;
        }
    }

    private ArrayList<VideoConvertMessage> videoConvertQueue = new ArrayList<>();
    private final Object videoQueueSync = new Object();
    private HashMap<String, MessageObject> generatingWaveform = new HashMap<>();

    private boolean voiceMessagesPlaylistUnread;
    private ArrayList<MessageObject> voiceMessagesPlaylist;
    private SparseArray<MessageObject> voiceMessagesPlaylistMap;

    private static Runnable refreshGalleryRunnable;
    public static AlbumEntry allMediaAlbumEntry;
    public static AlbumEntry allPhotosAlbumEntry;
    public static AlbumEntry allVideosAlbumEntry;
    public static ArrayList<AlbumEntry> allMediaAlbums = new ArrayList<>();
    public static ArrayList<AlbumEntry> allPhotoAlbums = new ArrayList<>();
    private static Runnable broadcastPhotosRunnable;

    public boolean isSilent = false;
    private boolean isPaused = false;
    private VideoPlayer audioPlayer = null;
    private VideoPlayer emojiSoundPlayer = null;
    private int emojiSoundPlayerNum = 0;
    private boolean isStreamingCurrentAudio;
    private int playerNum;
    private String shouldSavePositionForCurrentAudio;
    private long lastSaveTime;
    private float currentPlaybackSpeed = 1.0f;
    private float currentMusicPlaybackSpeed = 1.0f;
    private float fastPlaybackSpeed = 1.0f;
    private float fastMusicPlaybackSpeed = 1.0f;
    private float seekToProgressPending;
    private long lastProgress = 0;
    private MessageObject playingMessageObject;
    private MessageObject goingToShowMessageObject;
    private Timer progressTimer = null;
    private final Object progressTimerSync = new Object();
    private boolean downloadingCurrentMessage;
    private boolean playMusicAgain;
    private PlaylistGlobalSearchParams playlistGlobalSearchParams;
    private AudioInfo audioInfo;
    private VideoPlayer videoPlayer;
    private boolean playerWasReady;
    private TextureView currentTextureView;
    private PipRoundVideoView pipRoundVideoView;
    private int pipSwitchingState;
    private Activity baseActivity;
    private BaseFragment flagSecureFragment;
    private View feedbackView;
    private AspectRatioFrameLayout currentAspectRatioFrameLayout;
    private boolean isDrawingWasReady;
    private FrameLayout currentTextureViewContainer;
    private int currentAspectRatioFrameLayoutRotation;
    private float currentAspectRatioFrameLayoutRatio;
    private boolean currentAspectRatioFrameLayoutReady;

    private ArrayList<MessageObject> playlist = new ArrayList<>();
    private HashMap<Integer, MessageObject> playlistMap = new HashMap<>();
    private ArrayList<MessageObject> shuffledPlaylist = new ArrayList<>();
    private int currentPlaylistNum;
    private boolean forceLoopCurrentPlaylist;
    private boolean[] playlistEndReached = new boolean[]{false, false};
    private boolean loadingPlaylist;
    private long playlistMergeDialogId;
    private int playlistClassGuid;
    private int[] playlistMaxId = new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE};

    private Runnable setLoadingRunnable = new Runnable() {
        @Override
        public void run() {
            if (playingMessageObject == null) {
                return;
            }
            FileLoader.getInstance(playingMessageObject.currentAccount).setLoadingVideo(playingMessageObject.getDocument(), true, false);
        }
    };

    private AudioRecord audioRecorder;
    private TLRPC.TL_document recordingAudio;
    private int recordingGuid = -1;
    private int recordingCurrentAccount;
    private File recordingAudioFile;
    private long recordStartTime;
    private long recordTimeCount;
    private long recordDialogId;
    private MessageObject recordReplyingMsg;
    private MessageObject recordReplyingTopMsg;
    private short[] recordSamples = new short[1024];
    private long samplesCount;

    private final Object sync = new Object();

    private ArrayList<ByteBuffer> recordBuffers = new ArrayList<>();
    private ByteBuffer fileBuffer;
    public int recordBufferSize = 1280;
    public int sampleRate = 48000;
    private int sendAfterDone;
    private boolean sendAfterDoneNotify;
    private int sendAfterDoneScheduleDate;

    private Runnable recordStartRunnable;
    private DispatchQueue recordQueue;
    private DispatchQueue fileEncodingQueue;
    private Runnable recordRunnable = new Runnable() {
        @Override
        public void run() {
            if (audioRecorder != null) {
                ByteBuffer buffer;
                if (!recordBuffers.isEmpty()) {
                    buffer = recordBuffers.get(0);
                    recordBuffers.remove(0);
                } else {
                    buffer = ByteBuffer.allocateDirect(recordBufferSize);
                    buffer.order(ByteOrder.nativeOrder());
                }
                buffer.rewind();
                int len = audioRecorder.read(buffer, buffer.capacity());
                if (len > 0) {
                    buffer.limit(len);
                    double sum = 0;
                    try {
                        long newSamplesCount = samplesCount + len / 2;
                        int currentPart = (int) (((double) samplesCount / (double) newSamplesCount) * recordSamples.length);
                        int newPart = recordSamples.length - currentPart;
                        float sampleStep;
                        if (currentPart != 0) {
                            sampleStep = (float) recordSamples.length / (float) currentPart;
                            float currentNum = 0;
                            for (int a = 0; a < currentPart; a++) {
                                recordSamples[a] = recordSamples[(int) currentNum];
                                currentNum += sampleStep;
                            }
                        }
                        int currentNum = currentPart;
                        float nextNum = 0;
                        sampleStep = (float) len / 2 / (float) newPart;
                        for (int i = 0; i < len / 2; i++) {
                            short peak = buffer.getShort();
                            if (Build.VERSION.SDK_INT < 21) {
                                if (peak > 2500) {
                                    sum += peak * peak;
                                }
                            } else {
                                sum += peak * peak;
                            }
                            if (i == (int) nextNum && currentNum < recordSamples.length) {
                                recordSamples[currentNum] = peak;
                                nextNum += sampleStep;
                                currentNum++;
                            }
                        }
                        samplesCount = newSamplesCount;
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    buffer.position(0);
                    final double amplitude = Math.sqrt(sum / len / 2);
                    final ByteBuffer finalBuffer = buffer;
                    final boolean flush = len != buffer.capacity();
                    fileEncodingQueue.postRunnable(() -> {
                        while (finalBuffer.hasRemaining()) {
                            int oldLimit = -1;
                            if (finalBuffer.remaining() > fileBuffer.remaining()) {
                                oldLimit = finalBuffer.limit();
                                finalBuffer.limit(fileBuffer.remaining() + finalBuffer.position());
                            }
                            fileBuffer.put(finalBuffer);
                            if (fileBuffer.position() == fileBuffer.limit() || flush) {
                                if (writeFrame(fileBuffer, !flush ? fileBuffer.limit() : finalBuffer.position()) != 0) {
                                    fileBuffer.rewind();
                                    recordTimeCount += fileBuffer.limit() / 2 / (sampleRate / 1000);
                                }
                            }
                            if (oldLimit != -1) {
                                finalBuffer.limit(oldLimit);
                            }
                        }
                        recordQueue.postRunnable(() -> recordBuffers.add(finalBuffer));
                    });
                    recordQueue.postRunnable(recordRunnable);
                    AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(recordingCurrentAccount).postNotificationName(NotificationCenter.recordProgressChanged, recordingGuid, amplitude));
                } else {
                    recordBuffers.add(buffer);
                    if (sendAfterDone != 3) {
                        stopRecordingInternal(sendAfterDone, sendAfterDoneNotify, sendAfterDoneScheduleDate);
                    }
                }
            }
        }
    };

    private float audioVolume;
    private ValueAnimator audioVolumeAnimator;

    private final ValueAnimator.AnimatorUpdateListener audioVolumeUpdateListener = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator valueAnimator) {
            audioVolume = (float) valueAnimator.getAnimatedValue();
            setPlayerVolume();
        }
    };

    private class InternalObserver extends ContentObserver {
        public InternalObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            processMediaObserver(MediaStore.Images.Media.INTERNAL_CONTENT_URI);
        }
    }

    private class ExternalObserver extends ContentObserver {
        public ExternalObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            processMediaObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        }
    }

    private static class GalleryObserverInternal extends ContentObserver {
        public GalleryObserverInternal() {
            super(null);
        }

        private void scheduleReloadRunnable() {
            AndroidUtilities.runOnUIThread(refreshGalleryRunnable = () -> {
                if (PhotoViewer.getInstance().isVisible()) {
                    scheduleReloadRunnable();
                    return;
                }
                refreshGalleryRunnable = null;
                loadGalleryPhotosAlbums(0);
            }, 2000);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            if (refreshGalleryRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(refreshGalleryRunnable);
            }
            scheduleReloadRunnable();
        }
    }

    private static class GalleryObserverExternal extends ContentObserver {
        public GalleryObserverExternal() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            if (refreshGalleryRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(refreshGalleryRunnable);
            }
            AndroidUtilities.runOnUIThread(refreshGalleryRunnable = () -> {
                refreshGalleryRunnable = null;
                loadGalleryPhotosAlbums(0);
            }, 2000);
        }
    }

    public static void checkGallery() {
        if (Build.VERSION.SDK_INT < 24 || allPhotosAlbumEntry == null) {
            return;
        }
        final int prevSize = allPhotosAlbumEntry.photos.size();
        Utilities.globalQueue.postRunnable(() -> {
            int count = 0;
            Cursor cursor = null;
            try {
                if (ApplicationLoader.applicationContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    cursor = MediaStore.Images.Media.query(ApplicationLoader.applicationContext.getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[]{"COUNT(_id)"}, null, null, null);
                    if (cursor != null) {
                        if (cursor.moveToNext()) {
                            count += cursor.getInt(0);
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            try {
                if (ApplicationLoader.applicationContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    cursor = MediaStore.Images.Media.query(ApplicationLoader.applicationContext.getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI, new String[]{"COUNT(_id)"}, null, null, null);
                    if (cursor != null) {
                        if (cursor.moveToNext()) {
                            count += cursor.getInt(0);
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            if (prevSize != count) {
                if (refreshGalleryRunnable != null) {
                    AndroidUtilities.cancelRunOnUIThread(refreshGalleryRunnable);
                    refreshGalleryRunnable = null;
                }
                loadGalleryPhotosAlbums(0);
            }
        }, 2000);
    }


    private ExternalObserver externalObserver;
    private InternalObserver internalObserver;
    private long lastChatEnterTime;
    private int lastChatAccount;
    private long lastChatLeaveTime;
    private long lastMediaCheckTime;
    private TLRPC.EncryptedChat lastSecretChat;
    private TLRPC.User lastUser;
    private int lastMessageId;
    private ArrayList<Long> lastChatVisibleMessages;
    private int startObserverToken;
    private StopMediaObserverRunnable stopMediaObserverRunnable;

    private final class StopMediaObserverRunnable implements Runnable {
        public int currentObserverToken = 0;

        @Override
        public void run() {
            if (currentObserverToken == startObserverToken) {
                try {
                    if (internalObserver != null) {
                        ApplicationLoader.applicationContext.getContentResolver().unregisterContentObserver(internalObserver);
                        internalObserver = null;
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
                try {
                    if (externalObserver != null) {
                        ApplicationLoader.applicationContext.getContentResolver().unregisterContentObserver(externalObserver);
                        externalObserver = null;
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
    }

    private String[] mediaProjections;

    private static volatile MediaController Instance;

    public static MediaController getInstance() {
        MediaController localInstance = Instance;
        if (localInstance == null) {
            synchronized (MediaController.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new MediaController();
                }
            }
        }
        return localInstance;
    }

    public void recreateProximityWakeLock() {

        if (NekoConfig.disableProximityEvents.Bool()) {
            proximityWakeLock = null;
            return;
        }

        try {
            PowerManager powerManager = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            proximityWakeLock = powerManager.newWakeLock(0x00000020, "telegram:proximity_lock");
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public MediaController() {
        recordQueue = new DispatchQueue("recordQueue");
        recordQueue.setPriority(Thread.MAX_PRIORITY);
        fileEncodingQueue = new DispatchQueue("fileEncodingQueue");
        fileEncodingQueue.setPriority(Thread.MAX_PRIORITY);

        recordQueue.postRunnable(() -> {
            try {
                sampleRate = 48000;
                int minBuferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minBuferSize <= 0) {
                    minBuferSize = 1280;
                }
                recordBufferSize = minBuferSize;

                for (int a = 0; a < 5; a++) {
                    ByteBuffer buffer = ByteBuffer.allocateDirect(recordBufferSize);
                    buffer.order(ByteOrder.nativeOrder());
                    recordBuffers.add(buffer);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
        Utilities.globalQueue.postRunnable(() -> {
            try {
                currentPlaybackSpeed = MessagesController.getGlobalMainSettings().getFloat("playbackSpeed", 1.0f);
                currentMusicPlaybackSpeed = MessagesController.getGlobalMainSettings().getFloat("musicPlaybackSpeed", 1.0f);
                fastPlaybackSpeed = MessagesController.getGlobalMainSettings().getFloat("fastPlaybackSpeed", 1.8f);
                fastMusicPlaybackSpeed = MessagesController.getGlobalMainSettings().getFloat("fastMusicPlaybackSpeed", 1.8f);
                sensorManager = (SensorManager) ApplicationLoader.applicationContext.getSystemService(Context.SENSOR_SERVICE);
                linearSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
                gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
                if (linearSensor == null || gravitySensor == null) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("gravity or linear sensor not found");
                    }
                    accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                    linearSensor = null;
                    gravitySensor = null;
                }
                proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
                recreateProximityWakeLock();
            } catch (Exception e) {
                FileLog.e(e);
            }

            try {
                PhoneStateListener phoneStateListener = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(final int state, String incomingNumber) {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (state == TelephonyManager.CALL_STATE_RINGING) {
                                if (isPlayingMessage(playingMessageObject) && !isMessagePaused()) {
                                    pauseMessage(playingMessageObject);
                                } else if (recordStartRunnable != null || recordingAudio != null) {
                                    stopRecording(2, false, 0);
                                }
                                EmbedBottomSheet embedBottomSheet = EmbedBottomSheet.getInstance();
                                if (embedBottomSheet != null) {
                                    embedBottomSheet.pause();
                                }
                                callInProgress = true;
                            } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                                callInProgress = false;
                            } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                                EmbedBottomSheet embedBottomSheet = EmbedBottomSheet.getInstance();
                                if (embedBottomSheet != null) {
                                    embedBottomSheet.pause();
                                }
                                callInProgress = true;
                            }
                        });
                    }
                };
                TelephonyManager mgr = (TelephonyManager) ApplicationLoader.applicationContext.getSystemService(Context.TELEPHONY_SERVICE);
                if (mgr != null) {
                    mgr.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });

        fileBuffer = ByteBuffer.allocateDirect(1920);

        AndroidUtilities.runOnUIThread(() -> {
            for (int a : SharedConfig.activeAccounts) {
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.fileLoaded);
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.httpFileDidLoad);
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.didReceiveNewMessages);
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.messagesDeleted);
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.removeAllMessagesFromDialog);
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.musicDidLoad);
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.mediaDidLoad);
                NotificationCenter.getGlobalInstance().addObserver(MediaController.this, NotificationCenter.playerDidStartPlaying);
            }
        });

        mediaProjections = new String[]{
                MediaStore.Images.ImageColumns.DATA,
                MediaStore.Images.ImageColumns.DISPLAY_NAME,
                MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
                Build.VERSION.SDK_INT > 28 ? MediaStore.Images.ImageColumns.DATE_MODIFIED : MediaStore.Images.ImageColumns.DATE_TAKEN,
                MediaStore.Images.ImageColumns.TITLE,
                MediaStore.Images.ImageColumns.WIDTH,
                MediaStore.Images.ImageColumns.HEIGHT
        };

        ContentResolver contentResolver = ApplicationLoader.applicationContext.getContentResolver();
        try {
            contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, new GalleryObserverExternal());
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            contentResolver.registerContentObserver(MediaStore.Images.Media.INTERNAL_CONTENT_URI, true, new GalleryObserverInternal());
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, new GalleryObserverExternal());
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            contentResolver.registerContentObserver(MediaStore.Video.Media.INTERNAL_CONTENT_URI, true, new GalleryObserverInternal());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        AndroidUtilities.runOnUIThread(() -> {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                if (isPlayingMessage(getPlayingMessageObject()) && !isMessagePaused()) {
                    pauseMessage(playingMessageObject);
                }
                hasAudioFocus = 0;
                audioFocus = AUDIO_NO_FOCUS_NO_DUCK;
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                audioFocus = AUDIO_FOCUSED;
                if (resumeAudioOnFocusGain) {
                    resumeAudioOnFocusGain = false;
                    if (isPlayingMessage(getPlayingMessageObject()) && isMessagePaused()) {
                        playMessage(getPlayingMessageObject());
                    }
                }
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                audioFocus = AUDIO_NO_FOCUS_CAN_DUCK;
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                audioFocus = AUDIO_NO_FOCUS_NO_DUCK;
                if (isPlayingMessage(getPlayingMessageObject()) && !isMessagePaused()) {
                    pauseMessage(playingMessageObject);
                    resumeAudioOnFocusGain = true;
                }
            }
            setPlayerVolume();
        });
    }

    private void setPlayerVolume() {
        try {
            float volume;
            if (isSilent) {
                volume = 0;
            } else if (audioFocus != AUDIO_NO_FOCUS_CAN_DUCK) {
                volume = VOLUME_NORMAL;
            } else {
                volume = VOLUME_DUCK;
            }
            if (audioPlayer != null) {
                audioPlayer.setVolume(volume * audioVolume);
            } else if (videoPlayer != null) {
                videoPlayer.setVolume(volume);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public VideoPlayer getVideoPlayer() {
        return videoPlayer;
    }

    private void startProgressTimer(final MessageObject currentPlayingMessageObject) {
        synchronized (progressTimerSync) {
            if (progressTimer != null) {
                try {
                    progressTimer.cancel();
                    progressTimer = null;
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            final String fileName = currentPlayingMessageObject.getFileName();
            progressTimer = new Timer();
            progressTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    synchronized (sync) {
                        AndroidUtilities.runOnUIThread(() -> {
                            if ((audioPlayer != null || videoPlayer != null) && !isPaused) {
                                try {
                                    long duration;
                                    long progress;
                                    float value;
                                    float bufferedValue;
                                    if (videoPlayer != null) {
                                        duration = videoPlayer.getDuration();
                                        progress = videoPlayer.getCurrentPosition();
                                        if (progress < 0 || duration <= 0) {
                                            return;
                                        }
                                        bufferedValue = videoPlayer.getBufferedPosition() / (float) duration;
                                        value = progress / (float) duration;
                                        if (value >= 1) {
                                            return;
                                        }
                                    } else {
                                        duration = audioPlayer.getDuration();
                                        progress = audioPlayer.getCurrentPosition();
                                        value = duration >= 0 ? (progress / (float) duration) : 0.0f;
                                        bufferedValue = audioPlayer.getBufferedPosition() / (float) duration;
                                        if (duration == C.TIME_UNSET || progress < 0 || seekToProgressPending != 0) {
                                            return;
                                        }
                                    }
                                    lastProgress = progress;
                                    currentPlayingMessageObject.audioPlayerDuration = (int) (duration / 1000);
                                    currentPlayingMessageObject.audioProgress = value;
                                    currentPlayingMessageObject.audioProgressSec = (int) (lastProgress / 1000);
                                    currentPlayingMessageObject.bufferedProgress = bufferedValue;
                                    if (value >= 0 && shouldSavePositionForCurrentAudio != null && SystemClock.elapsedRealtime() - lastSaveTime >= 1000) {
                                        final String saveFor = shouldSavePositionForCurrentAudio;
                                        lastSaveTime = SystemClock.elapsedRealtime();
                                        Utilities.globalQueue.postRunnable(() -> {
                                            SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences("media_saved_pos", Activity.MODE_PRIVATE).edit();
                                            editor.putFloat(saveFor, value).commit();
                                        });
                                    }
                                    NotificationCenter.getInstance(currentPlayingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, currentPlayingMessageObject.getId(), value);
                                } catch (Exception e) {
                                    FileLog.e(e);
                                }
                            }
                        });
                    }
                }
            }, 0, 17);
        }
    }

    private void stopProgressTimer() {
        synchronized (progressTimerSync) {
            if (progressTimer != null) {
                try {
                    progressTimer.cancel();
                    progressTimer = null;
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
    }

    public void cleanup() {
        cleanupPlayer(true, true);
        audioInfo = null;
        playMusicAgain = false;
        for (int a : SharedConfig.activeAccounts) {
            DownloadController.getInstance(a).cleanup();
        }
        videoConvertQueue.clear();
        generatingWaveform.clear();
        voiceMessagesPlaylist = null;
        voiceMessagesPlaylistMap = null;
        clearPlaylist();
        cancelVideoConvert(null);
    }

    private void clearPlaylist() {
        playlist.clear();
        playlistMap.clear();
        shuffledPlaylist.clear();
        playlistClassGuid = 0;
        playlistEndReached[0] = playlistEndReached[1] = false;
        playlistMergeDialogId = 0;
        playlistMaxId[0] = playlistMaxId[1] = Integer.MAX_VALUE;
        loadingPlaylist = false;
        playlistGlobalSearchParams = null;
    }

    public void startMediaObserver() {
        ApplicationLoader.applicationHandler.removeCallbacks(stopMediaObserverRunnable);
        startObserverToken++;
        try {
            if (internalObserver == null) {
                ApplicationLoader.applicationContext.getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, externalObserver = new ExternalObserver());
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            if (externalObserver == null) {
                ApplicationLoader.applicationContext.getContentResolver().registerContentObserver(MediaStore.Images.Media.INTERNAL_CONTENT_URI, false, internalObserver = new InternalObserver());
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void stopMediaObserver() {
        if (stopMediaObserverRunnable == null) {
            stopMediaObserverRunnable = new StopMediaObserverRunnable();
        }
        stopMediaObserverRunnable.currentObserverToken = startObserverToken;
        ApplicationLoader.applicationHandler.postDelayed(stopMediaObserverRunnable, 5000);
    }

    private void processMediaObserver(Uri uri) {
        Cursor cursor = null;
        try {
            Point size = AndroidUtilities.getRealScreenSize();

            cursor = ApplicationLoader.applicationContext.getContentResolver().query(uri, mediaProjections, null, null, "date_added DESC LIMIT 1");
            final ArrayList<Long> screenshotDates = new ArrayList<>();
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String val = "";
                    String data = cursor.getString(0);
                    String display_name = cursor.getString(1);
                    String album_name = cursor.getString(2);
                    long date = cursor.getLong(3);
                    String title = cursor.getString(4);
                    int photoW = cursor.getInt(5);
                    int photoH = cursor.getInt(6);
                    if (data != null && data.toLowerCase().contains("screenshot") ||
                            display_name != null && display_name.toLowerCase().contains("screenshot") ||
                            album_name != null && album_name.toLowerCase().contains("screenshot") ||
                            title != null && title.toLowerCase().contains("screenshot")) {
                        try {
                            if (photoW == 0 || photoH == 0) {
                                BitmapFactory.Options bmOptions = new BitmapFactory.Options();
                                bmOptions.inJustDecodeBounds = true;
                                BitmapFactory.decodeFile(data, bmOptions);
                                photoW = bmOptions.outWidth;
                                photoH = bmOptions.outHeight;
                            }
                            if (photoW <= 0 || photoH <= 0 || (photoW == size.x && photoH == size.y || photoH == size.x && photoW == size.y)) {
                                screenshotDates.add(date);
                            }
                        } catch (Exception e) {
                            screenshotDates.add(date);
                        }
                    }
                }
                cursor.close();
            }
            if (!screenshotDates.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getInstance(lastChatAccount).postNotificationName(NotificationCenter.screenshotTook);
                    checkScreenshots(screenshotDates);
                });
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            try {
                if (cursor != null) {
                    cursor.close();
                }
            } catch (Exception ignore) {

            }
        }
    }

    private void checkScreenshots(ArrayList<Long> dates) {
        if (dates == null || dates.isEmpty() || lastChatEnterTime == 0 || (lastUser == null && !(lastSecretChat instanceof TLRPC.TL_encryptedChat))) {
            return;
        }
        long dt = 2000;
        boolean send = false;
        for (int a = 0; a < dates.size(); a++) {
            Long date = dates.get(a);
            if (lastMediaCheckTime != 0 && date <= lastMediaCheckTime) {
                continue;
            }

            if (date >= lastChatEnterTime) {
                if (lastChatLeaveTime == 0 || date <= lastChatLeaveTime + dt) {
                    lastMediaCheckTime = Math.max(lastMediaCheckTime, date);
                    send = true;
                }
            }
        }
        if (send && !NekoXConfig.disableScreenshotDetection) {
            if (lastSecretChat != null) {
                SecretChatHelper.getInstance(lastChatAccount).sendScreenshotMessage(lastSecretChat, lastChatVisibleMessages, null);
            } else {
                SendMessagesHelper.getInstance(lastChatAccount).sendScreenshotMessage(lastUser, lastMessageId, null);
            }
        }
    }

    public ArrayList<Long> getLastVisibleMessageIds() {
        return lastChatVisibleMessages;
    }

    public void setLastVisibleMessageIds(int account, long enterTime, long leaveTime, TLRPC.User user, TLRPC.EncryptedChat encryptedChat, ArrayList<Long> visibleMessages, int visibleMessage) {
        lastChatEnterTime = enterTime;
        lastChatLeaveTime = leaveTime;
        lastChatAccount = account;
        lastSecretChat = encryptedChat;
        lastUser = user;
        lastMessageId = visibleMessage;
        lastChatVisibleMessages = visibleMessages;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.fileLoaded || id == NotificationCenter.httpFileDidLoad) {
            String fileName = (String) args[0];
            if (playingMessageObject != null && playingMessageObject.currentAccount == account) {
                String file = FileLoader.getAttachFileName(playingMessageObject.getDocument());
                if (file.equals(fileName)) {
                    if (downloadingCurrentMessage) {
                        playMusicAgain = true;
                        playMessage(playingMessageObject);
                    } else if (audioInfo == null) {
                        try {
                            File cacheFile = FileLoader.getInstance(UserConfig.selectedAccount).getPathToMessage(playingMessageObject.messageOwner);
                            audioInfo = AudioInfo.getAudioInfo(cacheFile);
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                }
            }
        } else if (id == NotificationCenter.messagesDeleted) {
            boolean scheduled = (Boolean) args[2];
            if (scheduled) {
                return;
            }
            long channelId = (Long) args[1];
            ArrayList<Integer> markAsDeletedMessages = (ArrayList<Integer>) args[0];
            if (playingMessageObject != null) {
                if (channelId == playingMessageObject.messageOwner.peer_id.channel_id) {
                    if (markAsDeletedMessages.contains(playingMessageObject.getId())) {
                        cleanupPlayer(true, true);
                    }
                }
            }
            if (voiceMessagesPlaylist != null && !voiceMessagesPlaylist.isEmpty()) {
                MessageObject messageObject = voiceMessagesPlaylist.get(0);
                if (channelId == messageObject.messageOwner.peer_id.channel_id) {
                    for (int a = 0; a < markAsDeletedMessages.size(); a++) {
                        Integer key = markAsDeletedMessages.get(a);
                        messageObject = voiceMessagesPlaylistMap.get(key);
                        voiceMessagesPlaylistMap.remove(key);
                        if (messageObject != null) {
                            voiceMessagesPlaylist.remove(messageObject);
                        }
                    }
                }
            }
        } else if (id == NotificationCenter.removeAllMessagesFromDialog) {
            long did = (Long) args[0];
            if (playingMessageObject != null && playingMessageObject.getDialogId() == did) {
                cleanupPlayer(false, true);
            }
        } else if (id == NotificationCenter.musicDidLoad) {
            long did = (Long) args[0];
            if (playingMessageObject != null && playingMessageObject.isMusic() && playingMessageObject.getDialogId() == did && !playingMessageObject.scheduled) {
                ArrayList<MessageObject> arrayListBegin = (ArrayList<MessageObject>) args[1];
                ArrayList<MessageObject> arrayListEnd = (ArrayList<MessageObject>) args[2];
                playlist.addAll(0, arrayListBegin);
                playlist.addAll(arrayListEnd);
                for (int a = 0, N = playlist.size(); a < N; a++) {
                    MessageObject object = playlist.get(a);
                    playlistMap.put(object.getId(), object);
                    playlistMaxId[0] = Math.min(playlistMaxId[0], object.getId());
                }
                sortPlaylist();
                if (SharedConfig.shuffleMusic) {
                    buildShuffledPlayList();
                } else if (playingMessageObject != null) {
                    int newIndex = playlist.indexOf(playingMessageObject);
                    if (newIndex >= 0) {
                        currentPlaylistNum = newIndex;
                    }
                }
                playlistClassGuid = ConnectionsManager.generateClassGuid();
            }
        } else if (id == NotificationCenter.mediaDidLoad) {
            int guid = (Integer) args[3];
            if (guid == playlistClassGuid && playingMessageObject != null) {
                long did = (Long) args[0];
                int type = (Integer) args[4];

                ArrayList<MessageObject> arr = (ArrayList<MessageObject>) args[2];
                boolean enc = DialogObject.isEncryptedDialog(did);
                int loadIndex = did == playlistMergeDialogId ? 1 : 0;
                if (!arr.isEmpty()) {
                    playlistEndReached[loadIndex] = (Boolean) args[5];
                }
                int addedCount = 0;
                for (int a = 0; a < arr.size(); a++) {
                    MessageObject message = arr.get(a);
                    if (playlistMap.containsKey(message.getId())) {
                        continue;
                    }
                    addedCount++;
                    playlist.add(0, message);
                    playlistMap.put(message.getId(), message);
                    playlistMaxId[loadIndex] = Math.min(playlistMaxId[loadIndex], message.getId());
                }
                sortPlaylist();
                int newIndex = playlist.indexOf(playingMessageObject);
                if (newIndex >= 0) {
                    currentPlaylistNum = newIndex;
                }
                loadingPlaylist = false;
                if (SharedConfig.shuffleMusic) {
                    buildShuffledPlayList();
                }
                if (addedCount != 0) {
                    NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.moreMusicDidLoad, addedCount);
                }
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            boolean scheduled = (Boolean) args[2];
            if (scheduled) {
                return;
            }
            if (voiceMessagesPlaylist != null && !voiceMessagesPlaylist.isEmpty()) {
                MessageObject messageObject = voiceMessagesPlaylist.get(0);
                long did = (Long) args[0];
                if (did == messageObject.getDialogId()) {
                    ArrayList<MessageObject> arr = (ArrayList<MessageObject>) args[1];
                    for (int a = 0; a < arr.size(); a++) {
                        messageObject = arr.get(a);
                        if ((messageObject.isVoice() || messageObject.isRoundVideo()) && (!voiceMessagesPlaylistUnread || messageObject.isContentUnread() && !messageObject.isOut())) {
                            voiceMessagesPlaylist.add(messageObject);
                            voiceMessagesPlaylistMap.put(messageObject.getId(), messageObject);
                        }
                    }
                }
            }
        } else if (id == NotificationCenter.playerDidStartPlaying) {
            VideoPlayer p = (VideoPlayer) args[0];
            if (!MediaController.getInstance().isCurrentPlayer(p)) {
                MediaController.getInstance().pauseMessage(MediaController.getInstance().getPlayingMessageObject());
            }
        }
    }

    protected boolean isRecordingAudio() {
        return recordStartRunnable != null || recordingAudio != null;
    }

    private boolean isNearToSensor(float value) {
        return !NekoConfig.disableProximityEvents.Bool() && value < 5.0f && value != proximitySensor.getMaximumRange();
    }

    public boolean isRecordingOrListeningByProximity() {
        return proximityTouched && (isRecordingAudio() || playingMessageObject != null && (playingMessageObject.isVoice() || playingMessageObject.isRoundVideo()));
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!sensorsStarted || VoIPService.getSharedInstance() != null) {
            return;
        }
        if (event.sensor == proximitySensor) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("proximity changed to " + event.values[0] + " max value = " + proximitySensor.getMaximumRange());
            }
            if (lastProximityValue == -100) {
                lastProximityValue = event.values[0];
            } else if (lastProximityValue != event.values[0]) {
                proximityHasDifferentValues = true;
            }
            if (proximityHasDifferentValues) {
                proximityTouched = isNearToSensor(event.values[0]);
            }
        } else if (event.sensor == accelerometerSensor) {
            final double alpha = lastTimestamp == 0 ? 0.98f : 1.0 / (1.0 + (event.timestamp - lastTimestamp) / 1000000000.0);
            final float alphaFast = 0.8f;
            lastTimestamp = event.timestamp;
            gravity[0] = (float) (alpha * gravity[0] + (1.0 - alpha) * event.values[0]);
            gravity[1] = (float) (alpha * gravity[1] + (1.0 - alpha) * event.values[1]);
            gravity[2] = (float) (alpha * gravity[2] + (1.0 - alpha) * event.values[2]);
            gravityFast[0] = (alphaFast * gravity[0] + (1.0f - alphaFast) * event.values[0]);
            gravityFast[1] = (alphaFast * gravity[1] + (1.0f - alphaFast) * event.values[1]);
            gravityFast[2] = (alphaFast * gravity[2] + (1.0f - alphaFast) * event.values[2]);

            linearAcceleration[0] = event.values[0] - gravity[0];
            linearAcceleration[1] = event.values[1] - gravity[1];
            linearAcceleration[2] = event.values[2] - gravity[2];
        } else if (event.sensor == linearSensor) {
            linearAcceleration[0] = event.values[0];
            linearAcceleration[1] = event.values[1];
            linearAcceleration[2] = event.values[2];
        } else if (event.sensor == gravitySensor) {
            gravityFast[0] = gravity[0] = event.values[0];
            gravityFast[1] = gravity[1] = event.values[1];
            gravityFast[2] = gravity[2] = event.values[2];
        }
        final float minDist = 15.0f;
        final int minCount = 6;
        final int countLessMax = 10;
        if (event.sensor == linearSensor || event.sensor == gravitySensor || event.sensor == accelerometerSensor) {
            float val = gravity[0] * linearAcceleration[0] + gravity[1] * linearAcceleration[1] + gravity[2] * linearAcceleration[2];
            if (raisedToBack != minCount) {
                if (val > 0 && previousAccValue > 0 || val < 0 && previousAccValue < 0) {
                    boolean goodValue;
                    int sign;
                    if (val > 0) {
                        goodValue = val > minDist;
                        sign = 1;
                    } else {
                        goodValue = val < -minDist;
                        sign = 2;
                    }
                    if (raisedToTopSign != 0 && raisedToTopSign != sign) {
                        if (raisedToTop == minCount && goodValue) {
                            if (raisedToBack < minCount) {
                                raisedToBack++;
                                if (raisedToBack == minCount) {
                                    raisedToTop = 0;
                                    raisedToTopSign = 0;
                                    countLess = 0;
                                    timeSinceRaise = System.currentTimeMillis();
                                    if (BuildVars.LOGS_ENABLED && BuildVars.DEBUG_PRIVATE_VERSION) {
                                        FileLog.d("motion detected");
                                    }
                                }
                            }
                        } else {
                            if (!goodValue) {
                                countLess++;
                            }
                            if (countLess == countLessMax || raisedToTop != minCount || raisedToBack != 0) {
                                raisedToTop = 0;
                                raisedToTopSign = 0;
                                raisedToBack = 0;
                                countLess = 0;
                            }
                        }
                    } else {
                        if (goodValue && raisedToBack == 0 && (raisedToTopSign == 0 || raisedToTopSign == sign)) {
                            if (raisedToTop < minCount && !proximityTouched) {
                                raisedToTopSign = sign;
                                raisedToTop++;
                                if (raisedToTop == minCount) {
                                    countLess = 0;
                                }
                            }
                        } else {
                            if (!goodValue) {
                                countLess++;
                            }
                            if (raisedToTopSign != sign || countLess == countLessMax || raisedToTop != minCount || raisedToBack != 0) {
                                raisedToBack = 0;
                                raisedToTop = 0;
                                raisedToTopSign = 0;
                                countLess = 0;
                            }
                        }
                    }
                }
                /*if (val > 0 && previousAccValue > 0) {
                    if (val > minDist && raisedToBack == 0) {
                        if (raisedToTop < minCount && !proximityTouched) {
                            raisedToTop++;
                            if (raisedToTop == minCount) {
                                countLess = 0;
                            }
                        }
                    } else {
                        if (val < minDist) {
                            countLess++;
                        }
                        if (countLess == countLessMax || raisedToTop != minCount || raisedToBack != 0) {
                            raisedToBack = 0;
                            raisedToTop = 0;
                            countLess = 0;
                        }
                    }
                } else if (val < 0 && previousAccValue < 0) {
                    if (raisedToTop == minCount && val < -minDist) {
                        if (raisedToBack < minCount) {
                            raisedToBack++;
                            if (raisedToBack == minCount) {
                                raisedToTop = 0;
                                countLess = 0;
                                timeSinceRaise = System.currentTimeMillis();
                                if (BuildVars.LOGS_ENABLED && BuildVars.DEBUG_PRIVATE_VERSION) {
                                    FileLog.e("motion detected");
                                }
                            }
                        }
                    } else {
                        if (val > -minDist) {
                            countLess++;
                        }
                        if (countLess == countLessMax || raisedToTop != minCount || raisedToBack != 0) {
                            raisedToTop = 0;
                            raisedToBack = 0;
                            countLess = 0;
                        }
                    }
                }*/
                /*if (BuildVars.LOGS_ENABLED && BuildVars.DEBUG_PRIVATE_VERSION) {
                    FileLog.e("raise2 to top = " + raisedToTop + " to back = " + raisedToBack + " val = " + val + " countLess = " + countLess);
                }*/
            }
            previousAccValue = val;
            accelerometerVertical = gravityFast[1] > 2.5f && Math.abs(gravityFast[2]) < 4.0f && Math.abs(gravityFast[0]) > 1.5f;
            /*if (BuildVars.LOGS_ENABLED && BuildVars.DEBUG_PRIVATE_VERSION) {
                FileLog.d(accelerometerVertical + "    val = " + val + " acc (" + linearAcceleration[0] + ", " + linearAcceleration[1] + ", " + linearAcceleration[2] + ") grav (" + gravityFast[0] + ", " + gravityFast[1] + ", " + gravityFast[2] + ")");
            }*/
        }
        if (raisedToBack == minCount && accelerometerVertical && proximityTouched && !NotificationsController.audioManager.isWiredHeadsetOn()) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("sensor values reached");
            }
            if (playingMessageObject == null && recordStartRunnable == null && recordingAudio == null && !PhotoViewer.getInstance().isVisible() && ApplicationLoader.isScreenOn && !inputFieldHasText && allowStartRecord && raiseChat != null && !callInProgress) {
                if (!raiseToEarRecord) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("start record");
                    }
                    useFrontSpeaker = true;
                    if (!raiseChat.playFirstUnreadVoiceMessage()) {
                        raiseToEarRecord = true;
                        useFrontSpeaker = false;
                        startRecording(raiseChat.getCurrentAccount(), raiseChat.getDialogId(), null, raiseChat.getThreadMessage(), raiseChat.getClassGuid());
                    }
                    if (useFrontSpeaker) {
                        setUseFrontSpeaker(true);
                    }
                    ignoreOnPause = true;
                    if (proximityHasDifferentValues && proximityWakeLock != null && !proximityWakeLock.isHeld()) {
                        proximityWakeLock.acquire();
                    }
                }
            } else if (playingMessageObject != null && (playingMessageObject.isVoice() || playingMessageObject.isRoundVideo())) {
                if (!useFrontSpeaker) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("start listen");
                    }
                    if (proximityHasDifferentValues && proximityWakeLock != null && !proximityWakeLock.isHeld()) {
                        proximityWakeLock.acquire();
                    }
                    setUseFrontSpeaker(true);
                    startAudioAgain(false);
                    ignoreOnPause = true;
                }
            }
            raisedToBack = 0;
            raisedToTop = 0;
            raisedToTopSign = 0;
            countLess = 0;
        } else if (proximityTouched) {
            if (playingMessageObject != null && !ApplicationLoader.mainInterfacePaused && (playingMessageObject.isVoice() || playingMessageObject.isRoundVideo())) {
                if (!useFrontSpeaker && !NotificationsController.audioManager.isWiredHeadsetOn()) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("start listen by proximity only");
                    }
                    if (proximityHasDifferentValues && proximityWakeLock != null && !proximityWakeLock.isHeld()) {
                        proximityWakeLock.acquire();
                    }
                    setUseFrontSpeaker(true);
                    startAudioAgain(false);
                    ignoreOnPause = true;
                }
            }
        } else if (!proximityTouched) {
            if (raiseToEarRecord) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("stop record");
                }
                stopRecording(2, false, 0);
                raiseToEarRecord = false;
                ignoreOnPause = false;
                if (proximityHasDifferentValues && proximityWakeLock != null && proximityWakeLock.isHeld()) {
                    proximityWakeLock.release();
                }
            } else if (useFrontSpeaker) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("stop listen");
                }
                useFrontSpeaker = false;
                startAudioAgain(true);
                ignoreOnPause = false;
                if (proximityHasDifferentValues && proximityWakeLock != null && proximityWakeLock.isHeld()) {
                    proximityWakeLock.release();
                }
            }
        }
        if (timeSinceRaise != 0 && raisedToBack == minCount && Math.abs(System.currentTimeMillis() - timeSinceRaise) > 1000) {
            raisedToBack = 0;
            raisedToTop = 0;
            raisedToTopSign = 0;
            countLess = 0;
            timeSinceRaise = 0;
        }
    }

    private void setUseFrontSpeaker(boolean value) {
        useFrontSpeaker = value;
        AudioManager audioManager = NotificationsController.audioManager;
        if (useFrontSpeaker) {
            audioManager.setBluetoothScoOn(false);
            audioManager.setSpeakerphoneOn(false);
        } else {
            audioManager.setSpeakerphoneOn(true);
        }
    }

    public void startRecordingIfFromSpeaker() {
        if (!useFrontSpeaker || raiseChat == null || !allowStartRecord || !SharedConfig.raiseToSpeak) {
            return;
        }
        raiseToEarRecord = true;
        startRecording(raiseChat.getCurrentAccount(), raiseChat.getDialogId(), null, raiseChat.getThreadMessage(), raiseChat.getClassGuid());
        ignoreOnPause = true;
    }

    private void startAudioAgain(boolean paused) {
        if (playingMessageObject == null) {
            return;
        }

        NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.audioRouteChanged, useFrontSpeaker);
        if (videoPlayer != null) {
            videoPlayer.setStreamType(useFrontSpeaker ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
            if (!paused) {
                if (videoPlayer.getCurrentPosition() < 1000) {
                    videoPlayer.seekTo(0);
                }
                videoPlayer.play();
            } else {
                pauseMessage(playingMessageObject);
            }
        } else {
            boolean post = audioPlayer != null;
            final MessageObject currentMessageObject = playingMessageObject;
            float progress = playingMessageObject.audioProgress;
            int duration = playingMessageObject.audioPlayerDuration;
            if (paused || audioPlayer == null || !audioPlayer.isPlaying() || duration * progress > 1f) {
                currentMessageObject.audioProgress = progress;
            } else {
                currentMessageObject.audioProgress = 0;
            }
            cleanupPlayer(false, true);
            playMessage(currentMessageObject);
            if (paused) {
                if (post) {
                    AndroidUtilities.runOnUIThread(() -> pauseMessage(currentMessageObject), 100);
                } else {
                    pauseMessage(currentMessageObject);
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    public void setInputFieldHasText(boolean value) {
        inputFieldHasText = value;
    }

    public void setAllowStartRecord(boolean value) {
        allowStartRecord = value;
    }

    public void startRaiseToEarSensors(ChatActivity chatActivity) {
        if (chatActivity == null || accelerometerSensor == null && (gravitySensor == null || linearAcceleration == null) || proximitySensor == null) {
            return;
        }
        raiseChat = chatActivity;
        if (!SharedConfig.raiseToSpeak && (playingMessageObject == null || !playingMessageObject.isVoice() && !playingMessageObject.isRoundVideo())) {
            return;
        }
        if (!sensorsStarted) {
            gravity[0] = gravity[1] = gravity[2] = 0;
            linearAcceleration[0] = linearAcceleration[1] = linearAcceleration[2] = 0;
            gravityFast[0] = gravityFast[1] = gravityFast[2] = 0;
            lastTimestamp = 0;
            previousAccValue = 0;
            raisedToTop = 0;
            raisedToTopSign = 0;
            countLess = 0;
            raisedToBack = 0;
            Utilities.globalQueue.postRunnable(() -> {
                if (gravitySensor != null) {
                    sensorManager.registerListener(MediaController.this, gravitySensor, 30000);
                }
                if (linearSensor != null) {
                    sensorManager.registerListener(MediaController.this, linearSensor, 30000);
                }
                if (accelerometerSensor != null) {
                    sensorManager.registerListener(MediaController.this, accelerometerSensor, 30000);
                }
                sensorManager.registerListener(MediaController.this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            });
            sensorsStarted = true;
        }
    }

    public void stopRaiseToEarSensors(ChatActivity chatActivity, boolean fromChat) {
        if (ignoreOnPause) {
            ignoreOnPause = false;
            return;
        }
        stopRecording(fromChat ? 2 : 0, false, 0);
        if (!sensorsStarted || ignoreOnPause || accelerometerSensor == null && (gravitySensor == null || linearAcceleration == null) || proximitySensor == null || raiseChat != chatActivity) {
            return;
        }
        raiseChat = null;
        sensorsStarted = false;
        accelerometerVertical = false;
        proximityTouched = false;
        raiseToEarRecord = false;
        useFrontSpeaker = false;
        Utilities.globalQueue.postRunnable(() -> {
            if (linearSensor != null) {
                sensorManager.unregisterListener(MediaController.this, linearSensor);
            }
            if (gravitySensor != null) {
                sensorManager.unregisterListener(MediaController.this, gravitySensor);
            }
            if (accelerometerSensor != null) {
                sensorManager.unregisterListener(MediaController.this, accelerometerSensor);
            }
            sensorManager.unregisterListener(MediaController.this, proximitySensor);
        });
        if (proximityHasDifferentValues && proximityWakeLock != null && proximityWakeLock.isHeld()) {
            proximityWakeLock.release();
        }
    }

    public void cleanupPlayer(boolean notify, boolean stopService) {
        cleanupPlayer(notify, stopService, false, false);
    }

    public void cleanupPlayer(boolean notify, boolean stopService, boolean byVoiceEnd, boolean transferPlayerToPhotoViewer) {
        if (audioPlayer != null) {
            if (audioVolumeAnimator != null) {
                audioVolumeAnimator.removeAllUpdateListeners();
                audioVolumeAnimator.cancel();
            }

            if (audioPlayer.isPlaying() && playingMessageObject != null && !playingMessageObject.isVoice()) {
                VideoPlayer playerFinal = audioPlayer;
                ValueAnimator valueAnimator = ValueAnimator.ofFloat(audioVolume, 0);
                valueAnimator.addUpdateListener(valueAnimator1 -> {
                    float volume;
                    if (audioFocus != AUDIO_NO_FOCUS_CAN_DUCK) {
                        volume = VOLUME_NORMAL;
                    } else {
                        volume = VOLUME_DUCK;
                    }
                    playerFinal.setVolume(volume * (float) valueAnimator1.getAnimatedValue());
                });
                valueAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        try {
                            playerFinal.releasePlayer(true);
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                });
                valueAnimator.setDuration(300);
                valueAnimator.start();
            } else {
                try {
                    audioPlayer.releasePlayer(true);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            audioPlayer = null;
            Theme.unrefAudioVisualizeDrawable(playingMessageObject);
        } else if (videoPlayer != null) {
            currentAspectRatioFrameLayout = null;
            currentTextureViewContainer = null;
            currentAspectRatioFrameLayoutReady = false;
            isDrawingWasReady = false;
            currentTextureView = null;
            goingToShowMessageObject = null;
            if (transferPlayerToPhotoViewer) {
                PhotoViewer.getInstance().injectVideoPlayer(videoPlayer);
                goingToShowMessageObject = playingMessageObject;
                NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingGoingToStop, playingMessageObject, true);
            } else {
                long position = videoPlayer.getCurrentPosition();
                if (playingMessageObject != null && playingMessageObject.isVideo() && position > 0) {
                    playingMessageObject.audioProgressMs = (int) position;
                    NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingGoingToStop, playingMessageObject, false);
                }
                videoPlayer.releasePlayer(true);
                videoPlayer = null;
            }
            try {
                baseActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (playingMessageObject != null && !transferPlayerToPhotoViewer) {
                AndroidUtilities.cancelRunOnUIThread(setLoadingRunnable);
                FileLoader.getInstance(playingMessageObject.currentAccount).removeLoadingVideo(playingMessageObject.getDocument(), true, false);
            }
        }
        stopProgressTimer();
        lastProgress = 0;
        isPaused = false;
        if (!useFrontSpeaker && !SharedConfig.raiseToSpeak) {
            ChatActivity chat = raiseChat;
            stopRaiseToEarSensors(raiseChat, false);
            raiseChat = chat;
        }
        if (proximityWakeLock != null && proximityWakeLock.isHeld() && !proximityTouched) {
            proximityWakeLock.release();
        }
        if (playingMessageObject != null) {
            if (downloadingCurrentMessage) {
                FileLoader.getInstance(playingMessageObject.currentAccount).cancelLoadFile(playingMessageObject.getDocument());
            }
            MessageObject lastFile = playingMessageObject;
            if (notify) {
                playingMessageObject.resetPlayingProgress();
                NotificationCenter.getInstance(lastFile.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, playingMessageObject.getId(), 0);
            }
            playingMessageObject = null;
            downloadingCurrentMessage = false;
            if (notify) {
                NotificationsController.audioManager.abandonAudioFocus(this);
                hasAudioFocus = 0;
                int index = -1;
                if (voiceMessagesPlaylist != null) {
                    if (byVoiceEnd && (index = voiceMessagesPlaylist.indexOf(lastFile)) >= 0) {
                        voiceMessagesPlaylist.remove(index);
                        voiceMessagesPlaylistMap.remove(lastFile.getId());
                        if (voiceMessagesPlaylist.isEmpty()) {
                            voiceMessagesPlaylist = null;
                            voiceMessagesPlaylistMap = null;
                        }
                    } else {
                        voiceMessagesPlaylist = null;
                        voiceMessagesPlaylistMap = null;
                    }
                }
                boolean next = false;
                if (voiceMessagesPlaylist != null && index < voiceMessagesPlaylist.size()) {
                    MessageObject nextVoiceMessage = voiceMessagesPlaylist.get(index);
                    playMessage(nextVoiceMessage);
                    if (!nextVoiceMessage.isRoundVideo() && pipRoundVideoView != null) {
                        pipRoundVideoView.close(true);
                        pipRoundVideoView = null;
                    }
                } else {
                    if ((lastFile.isVoice() || lastFile.isRoundVideo()) && lastFile.getId() != 0) {
                        startRecordingIfFromSpeaker();
                    }
                    NotificationCenter.getInstance(lastFile.currentAccount).postNotificationName(NotificationCenter.messagePlayingDidReset, lastFile.getId(), stopService);
                    pipSwitchingState = 0;
                    if (pipRoundVideoView != null) {
                        pipRoundVideoView.close(true);
                        pipRoundVideoView = null;
                    }
                }
            }
            if (stopService) {
                Intent intent = new Intent(ApplicationLoader.applicationContext, MusicPlayerService.class);
                ApplicationLoader.applicationContext.stopService(intent);
            }
        }
    }

    public boolean isGoingToShowMessageObject(MessageObject messageObject) {
        return goingToShowMessageObject == messageObject;
    }

    public void resetGoingToShowMessageObject() {
        goingToShowMessageObject = null;
    }

    private boolean isSamePlayingMessage(MessageObject messageObject) {
        return playingMessageObject != null && playingMessageObject.getDialogId() == messageObject.getDialogId() && playingMessageObject.getId() == messageObject.getId() && ((playingMessageObject.eventId == 0) == (messageObject.eventId == 0));
    }

    public boolean seekToProgress(MessageObject messageObject, float progress) {
        if (audioPlayer == null && videoPlayer == null || messageObject == null || playingMessageObject == null || !isSamePlayingMessage(messageObject)) {
            return false;
        }
        try {
            if (audioPlayer != null) {
                long duration = audioPlayer.getDuration();
                if (duration == C.TIME_UNSET) {
                    seekToProgressPending = progress;
                } else {
                    playingMessageObject.audioProgress = progress;
                    int seekTo = (int) (duration * progress);
                    audioPlayer.seekTo(seekTo);
                    lastProgress = seekTo;
                }
            } else if (videoPlayer != null) {
                videoPlayer.seekTo((long) (videoPlayer.getDuration() * progress));
            }
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
        NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingDidSeek, playingMessageObject.getId(), progress);
        return true;
    }

    public long getDuration() {
        if (audioPlayer == null) {
            return 0;
        }
        return audioPlayer.getDuration();
    }

    public MessageObject getPlayingMessageObject() {
        return playingMessageObject;
    }

    public int getPlayingMessageObjectNum() {
        return currentPlaylistNum;
    }

    private void buildShuffledPlayList() {
        if (playlist.isEmpty()) {
            return;
        }
        ArrayList<MessageObject> all = new ArrayList<>(playlist);
        shuffledPlaylist.clear();

        MessageObject messageObject = playlist.get(currentPlaylistNum);
        all.remove(currentPlaylistNum);

        int count = all.size();
        for (int a = 0; a < count; a++) {
            int index = Utilities.random.nextInt(all.size());
            shuffledPlaylist.add(all.get(index));
            all.remove(index);
        }
        shuffledPlaylist.add(messageObject);
        currentPlaylistNum = shuffledPlaylist.size() - 1;
    }

    public void loadMoreMusic() {
        if (loadingPlaylist || playingMessageObject == null || playingMessageObject.scheduled || DialogObject.isEncryptedDialog(playingMessageObject.getDialogId()) || playlistClassGuid == 0) {
            return;
        }
        if (playlistGlobalSearchParams != null) {
            int finalPlaylistGuid = playlistClassGuid;
            if (!playlistGlobalSearchParams.endReached && !playlist.isEmpty()) {
                int currentAccount = playlist.get(0).currentAccount;
                TLObject request;
                if (playlistGlobalSearchParams.dialogId != 0) {
                    final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
                    req.q = playlistGlobalSearchParams.query;
                    req.limit = 20;
                    req.filter = playlistGlobalSearchParams.filter == null ? new TLRPC.TL_inputMessagesFilterEmpty() : playlistGlobalSearchParams.filter.filter;
                    req.peer = AccountInstance.getInstance(currentAccount).getMessagesController().getInputPeer(playlistGlobalSearchParams.dialogId);
                    MessageObject lastMessage = playlist.get(playlist.size() - 1);
                    req.offset_id = lastMessage.getId();
                    if (playlistGlobalSearchParams.minDate > 0) {
                        req.min_date = (int) (playlistGlobalSearchParams.minDate / 1000);
                    }
                    if (playlistGlobalSearchParams.maxDate > 0) {
                        req.min_date = (int) (playlistGlobalSearchParams.maxDate / 1000);
                    }
                    request = req;
                } else {
                    final TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
                    req.limit = 20;
                    req.q = playlistGlobalSearchParams.query;
                    req.filter = playlistGlobalSearchParams.filter.filter;
                    MessageObject lastMessage = playlist.get(playlist.size() - 1);
                    req.offset_id = lastMessage.getId();
                    req.offset_rate = playlistGlobalSearchParams.nextSearchRate;
                    req.flags |= 1;
                    req.folder_id = playlistGlobalSearchParams.folderId;
                    long id;
                    if (lastMessage.messageOwner.peer_id.channel_id != 0) {
                        id = -lastMessage.messageOwner.peer_id.channel_id;
                    } else if (lastMessage.messageOwner.peer_id.chat_id != 0) {
                        id = -lastMessage.messageOwner.peer_id.chat_id;
                    } else {
                        id = lastMessage.messageOwner.peer_id.user_id;
                    }
                    req.offset_peer = MessagesController.getInstance(currentAccount).getInputPeer(id);
                    if (playlistGlobalSearchParams.minDate > 0) {
                        req.min_date = (int) (playlistGlobalSearchParams.minDate / 1000);
                    }
                    if (playlistGlobalSearchParams.maxDate > 0) {
                        req.min_date = (int) (playlistGlobalSearchParams.maxDate / 1000);
                    }
                    request = req;
                }
                loadingPlaylist = true;
                ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (playlistClassGuid != finalPlaylistGuid || playlistGlobalSearchParams == null || playingMessageObject == null) {
                        return;
                    }
                    if (error != null) {
                        return;
                    }
                    loadingPlaylist = false;

                    TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    playlistGlobalSearchParams.nextSearchRate = res.next_rate;
                    MessagesStorage.getInstance(currentAccount).putUsersAndChats(res.users, res.chats, true, true);
                    MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                    MessagesController.getInstance(currentAccount).putChats(res.chats, false);
                    int n = res.messages.size();
                    int addedCount = 0;
                    for (int i = 0; i < n; i++) {
                        MessageObject messageObject = new MessageObject(currentAccount, res.messages.get(i), false, true);
                        if (playlistMap.containsKey(messageObject.getId())) {
                            continue;
                        }
                        playlist.add(0, messageObject);
                        playlistMap.put(messageObject.getId(), messageObject);
                        addedCount++;
                    }
                    sortPlaylist();
                    loadingPlaylist = false;
                    playlistGlobalSearchParams.endReached = playlist.size() == playlistGlobalSearchParams.totalCount;
                    if (SharedConfig.shuffleMusic) {
                        buildShuffledPlayList();
                    }
                    if (addedCount != 0) {
                        NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.moreMusicDidLoad, addedCount);
                    }
                }));
            }
            return;
        }
        //TODO topics
        if (!playlistEndReached[0]) {
            loadingPlaylist = true;
            AccountInstance.getInstance(playingMessageObject.currentAccount).getMediaDataController().loadMedia(playingMessageObject.getDialogId(), 50, playlistMaxId[0], 0, MediaDataController.MEDIA_MUSIC, 0, 1, playlistClassGuid, 0);
        } else if (playlistMergeDialogId != 0 && !playlistEndReached[1]) {
            loadingPlaylist = true;
            AccountInstance.getInstance(playingMessageObject.currentAccount).getMediaDataController().loadMedia(playlistMergeDialogId, 50, playlistMaxId[0], 0, MediaDataController.MEDIA_MUSIC, 0, 1, playlistClassGuid, 0);
        }
    }

    public boolean setPlaylist(ArrayList<MessageObject> messageObjects, MessageObject current, long mergeDialogId, PlaylistGlobalSearchParams globalSearchParams) {
        return setPlaylist(messageObjects, current, mergeDialogId, true, globalSearchParams);
    }

    public boolean setPlaylist(ArrayList<MessageObject> messageObjects, MessageObject current, long mergeDialogId) {
        return setPlaylist(messageObjects, current, mergeDialogId, true, null);
    }

    public boolean setPlaylist(ArrayList<MessageObject> messageObjects, MessageObject current, long mergeDialogId, boolean loadMusic, PlaylistGlobalSearchParams params) {
        if (playingMessageObject == current) {
            int newIdx = playlist.indexOf(current);
            if (newIdx >= 0) {
                currentPlaylistNum = newIdx;
            }
            return playMessage(current);
        }
        forceLoopCurrentPlaylist = !loadMusic;
        playlistMergeDialogId = mergeDialogId;
        playMusicAgain = !playlist.isEmpty();
        clearPlaylist();
        playlistGlobalSearchParams = params;
        boolean isSecretChat = !messageObjects.isEmpty() && DialogObject.isEncryptedDialog(messageObjects.get(0).getDialogId());
        int minId = Integer.MAX_VALUE;
        int maxId = Integer.MIN_VALUE;
        for (int a = messageObjects.size() - 1; a >= 0; a--) {
            MessageObject messageObject = messageObjects.get(a);
            if (messageObject.isMusic()) {
                int id = messageObject.getId();
                if (id > 0 || isSecretChat) {
                    minId = Math.min(minId, id);
                    maxId = Math.max(maxId, id);
                }
                playlist.add(messageObject);
                playlistMap.put(id, messageObject);
            }
        }
        sortPlaylist();
        currentPlaylistNum = playlist.indexOf(current);
        if (currentPlaylistNum == -1) {
            clearPlaylist();
            currentPlaylistNum = playlist.size();
            playlist.add(current);
            playlistMap.put(current.getId(), current);
        }
        if (current.isMusic() && !current.scheduled) {
            if (SharedConfig.shuffleMusic) {
                buildShuffledPlayList();
            }
            if (loadMusic) {
                if (playlistGlobalSearchParams == null) {
                    MediaDataController.getInstance(current.currentAccount).loadMusic(current.getDialogId(), minId, maxId);
                } else {
                    playlistClassGuid = ConnectionsManager.generateClassGuid();
                }
            }
        }
        return playMessage(current);
    }

    private void sortPlaylist() {
        Collections.sort(playlist, (o1, o2) -> {
            int mid1 = o1.getId();
            int mid2 = o2.getId();
            long group1 = o1.messageOwner.grouped_id;
            long group2 = o2.messageOwner.grouped_id;
            if (mid1 < 0 && mid2 < 0) {
                if (group1 != 0 && group1 == group2) {
                    return Integer.compare(mid1, mid2);
                }
                return Integer.compare(mid2, mid1);
            } else {
                if (group1 != 0 && group1 == group2) {
                    return Integer.compare(mid2, mid1);
                }
                return Integer.compare(mid1, mid2);
            }
        });
    }

    public void playNextMessage() {
        playNextMessageWithoutOrder(false);
    }

    public boolean findMessageInPlaylistAndPlay(MessageObject messageObject) {
        int index = playlist.indexOf(messageObject);
        if (index == -1) {
            return playMessage(messageObject);
        } else {
            playMessageAtIndex(index);
        }
        return true;
    }

    public void playMessageAtIndex(int index) {
        if (currentPlaylistNum < 0 || currentPlaylistNum >= playlist.size()) {
            return;
        }
        currentPlaylistNum = index;
        playMusicAgain = true;
        MessageObject messageObject = playlist.get(currentPlaylistNum);
        if (playingMessageObject != null && !isSamePlayingMessage(messageObject)) {
            playingMessageObject.resetPlayingProgress();
        }
        playMessage(messageObject);
    }

    private void playNextMessageWithoutOrder(boolean byStop) {
        ArrayList<MessageObject> currentPlayList = SharedConfig.shuffleMusic ? shuffledPlaylist : playlist;

        if (byStop && (SharedConfig.repeatMode == 2 || SharedConfig.repeatMode == 1 && currentPlayList.size() == 1) && !forceLoopCurrentPlaylist) {
            cleanupPlayer(false, false);
            MessageObject messageObject = currentPlayList.get(currentPlaylistNum);
            messageObject.audioProgress = 0;
            messageObject.audioProgressSec = 0;
            playMessage(messageObject);
            return;
        }

        boolean last = false;
        if (SharedConfig.playOrderReversed) {
            currentPlaylistNum++;
            if (currentPlaylistNum >= currentPlayList.size()) {
                currentPlaylistNum = 0;
                last = true;
            }
        } else {
            currentPlaylistNum--;
            if (currentPlaylistNum < 0) {
                currentPlaylistNum = currentPlayList.size() - 1;
                last = true;
            }
        }
        if (last && byStop && SharedConfig.repeatMode == 0 && !forceLoopCurrentPlaylist) {
            if (audioPlayer != null || videoPlayer != null) {
                if (audioPlayer != null) {
                    try {
                        audioPlayer.releasePlayer(true);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    audioPlayer = null;
                    Theme.unrefAudioVisualizeDrawable(playingMessageObject);
                } else {
                    currentAspectRatioFrameLayout = null;
                    currentTextureViewContainer = null;
                    currentAspectRatioFrameLayoutReady = false;
                    currentTextureView = null;
                    videoPlayer.releasePlayer(true);
                    videoPlayer = null;
                    try {
                        baseActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    AndroidUtilities.cancelRunOnUIThread(setLoadingRunnable);
                    FileLoader.getInstance(playingMessageObject.currentAccount).removeLoadingVideo(playingMessageObject.getDocument(), true, false);
                }
                stopProgressTimer();
                lastProgress = 0;
                isPaused = true;
                playingMessageObject.audioProgress = 0.0f;
                playingMessageObject.audioProgressSec = 0;
                NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, playingMessageObject.getId(), 0);
                NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingPlayStateChanged, playingMessageObject.getId());
            }
            return;
        }
        if (currentPlaylistNum < 0 || currentPlaylistNum >= currentPlayList.size()) {
            return;
        }
        if (playingMessageObject != null) {
            playingMessageObject.resetPlayingProgress();
        }
        playMusicAgain = true;
        playMessage(currentPlayList.get(currentPlaylistNum));
    }

    public void playPreviousMessage() {
        ArrayList<MessageObject> currentPlayList = SharedConfig.shuffleMusic ? shuffledPlaylist : playlist;
        if (currentPlayList.isEmpty() || currentPlaylistNum < 0 || currentPlaylistNum >= currentPlayList.size()) {
            return;
        }
        MessageObject currentSong = currentPlayList.get(currentPlaylistNum);
        if (currentSong.audioProgressSec > 10) {
            seekToProgress(currentSong, 0);
            return;
        }

        if (SharedConfig.playOrderReversed) {
            currentPlaylistNum--;
            if (currentPlaylistNum < 0) {
                currentPlaylistNum = currentPlayList.size() - 1;
            }
        } else {
            currentPlaylistNum++;
            if (currentPlaylistNum >= currentPlayList.size()) {
                currentPlaylistNum = 0;
            }
        }
        if (currentPlaylistNum >= currentPlayList.size()) {
            return;
        }
        playMusicAgain = true;
        playMessage(currentPlayList.get(currentPlaylistNum));
    }

    protected void checkIsNextMediaFileDownloaded() {
        if (playingMessageObject == null || !playingMessageObject.isMusic()) {
            return;
        }
        checkIsNextMusicFileDownloaded(playingMessageObject.currentAccount);
    }

    private void checkIsNextVoiceFileDownloaded(int currentAccount) {
        if (voiceMessagesPlaylist == null || voiceMessagesPlaylist.size() < 2) {
            return;
        }
        MessageObject nextAudio = voiceMessagesPlaylist.get(1);
        File file = null;
        if (nextAudio.messageOwner.attachPath != null && nextAudio.messageOwner.attachPath.length() > 0) {
            file = new File(nextAudio.messageOwner.attachPath);
            if (!file.exists()) {
                file = null;
            }
        }
        final File cacheFile = file != null ? file : FileLoader.getInstance(currentAccount).getPathToMessage(nextAudio.messageOwner);
        boolean exist = cacheFile.exists();
        if (cacheFile != file && !cacheFile.exists()) {
            FileLoader.getInstance(currentAccount).loadFile(nextAudio.getDocument(), nextAudio, FileLoader.PRIORITY_LOW, 0);
        }
    }

    private void checkIsNextMusicFileDownloaded(int currentAccount) {
        if (!DownloadController.getInstance(currentAccount).canDownloadNextTrack()) {
            return;
        }
        ArrayList<MessageObject> currentPlayList = SharedConfig.shuffleMusic ? shuffledPlaylist : playlist;
        if (currentPlayList == null || currentPlayList.size() < 2) {
            return;
        }
        int nextIndex;
        if (SharedConfig.playOrderReversed) {
            nextIndex = currentPlaylistNum + 1;
            if (nextIndex >= currentPlayList.size()) {
                nextIndex = 0;
            }
        } else {
            nextIndex = currentPlaylistNum - 1;
            if (nextIndex < 0) {
                nextIndex = currentPlayList.size() - 1;
            }
        }
        if (nextIndex < 0 || nextIndex >= currentPlayList.size()) {
            return;
        }

        MessageObject nextAudio = currentPlayList.get(nextIndex);
        File file = null;
        if (!TextUtils.isEmpty(nextAudio.messageOwner.attachPath)) {
            file = new File(nextAudio.messageOwner.attachPath);
            if (!file.exists()) {
                file = null;
            }
        }
        final File cacheFile = file != null ? file : FileLoader.getInstance(currentAccount).getPathToMessage(nextAudio.messageOwner);
        boolean exist = cacheFile.exists();
        if (cacheFile != file && !cacheFile.exists() && nextAudio.isMusic()) {
            FileLoader.getInstance(currentAccount).loadFile(nextAudio.getDocument(), nextAudio, FileLoader.PRIORITY_LOW, 0);
        }
    }

    public void setVoiceMessagesPlaylist(ArrayList<MessageObject> playlist, boolean unread) {
        voiceMessagesPlaylist = playlist;
        if (voiceMessagesPlaylist != null) {
            voiceMessagesPlaylistUnread = unread;
            voiceMessagesPlaylistMap = new SparseArray<>();
            for (int a = 0; a < voiceMessagesPlaylist.size(); a++) {
                MessageObject messageObject = voiceMessagesPlaylist.get(a);
                voiceMessagesPlaylistMap.put(messageObject.getId(), messageObject);
            }
        }
    }

    private void checkAudioFocus(MessageObject messageObject) {
        int neededAudioFocus;
        if (messageObject.isVoice() || messageObject.isRoundVideo()) {
            if (useFrontSpeaker) {
                neededAudioFocus = 3;
            } else {
                neededAudioFocus = 2;
            }
        } else {
            neededAudioFocus = 1;
        }
        if (hasAudioFocus != neededAudioFocus) {
            hasAudioFocus = neededAudioFocus;
            int result;
            if (neededAudioFocus == 3) {
                result = NotificationsController.audioManager.requestAudioFocus(this, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN);
            } else {
                result = NotificationsController.audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, neededAudioFocus == 2 ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK : AudioManager.AUDIOFOCUS_GAIN);
            }
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                audioFocus = AUDIO_FOCUSED;
            }
        }
    }

    public boolean isPiPShown() {
        return pipRoundVideoView != null;
    }

    public void setCurrentVideoVisible(boolean visible) {
        if (currentAspectRatioFrameLayout == null) {
            return;
        }
        if (visible) {
            if (pipRoundVideoView != null) {
                pipSwitchingState = 2;
                pipRoundVideoView.close(true);
                pipRoundVideoView = null;
            } else {
                if (currentAspectRatioFrameLayout.getParent() == null) {
                    currentTextureViewContainer.addView(currentAspectRatioFrameLayout);
                }
                videoPlayer.setTextureView(currentTextureView);
            }
        } else {
            if (currentAspectRatioFrameLayout.getParent() != null) {
                pipSwitchingState = 1;
                currentTextureViewContainer.removeView(currentAspectRatioFrameLayout);
            } else {
                if (pipRoundVideoView == null) {
                    try {
                        pipRoundVideoView = new PipRoundVideoView();
                        pipRoundVideoView.show(baseActivity, () -> cleanupPlayer(true, true));
                    } catch (Exception e) {
                        pipRoundVideoView = null;
                    }
                }
                if (pipRoundVideoView != null) {
                    videoPlayer.setTextureView(pipRoundVideoView.getTextureView());
                }
            }
        }
    }

    public void setTextureView(TextureView textureView, AspectRatioFrameLayout aspectRatioFrameLayout, FrameLayout container, boolean set) {
        setTextureView(textureView, aspectRatioFrameLayout, container, set, null);
    }

    public void setTextureView(TextureView textureView, AspectRatioFrameLayout aspectRatioFrameLayout, FrameLayout container, boolean set, Runnable afterPip) {
        if (textureView == null) {
            return;
        }
        if (!set && currentTextureView == textureView) {
            pipSwitchingState = 1;
            currentTextureView = null;
            currentAspectRatioFrameLayout = null;
            currentTextureViewContainer = null;
            return;
        }
        if (videoPlayer == null || textureView == currentTextureView) {
            return;
        }
        isDrawingWasReady = aspectRatioFrameLayout != null && aspectRatioFrameLayout.isDrawingReady();
        currentTextureView = textureView;
        if (afterPip != null && pipRoundVideoView == null) {
            try {
                pipRoundVideoView = new PipRoundVideoView();
                pipRoundVideoView.show(baseActivity, () -> cleanupPlayer(true, true));
            } catch (Exception e) {
                pipRoundVideoView = null;
            }
        }
        if (pipRoundVideoView != null) {
            videoPlayer.setTextureView(pipRoundVideoView.getTextureView());
        } else {
            videoPlayer.setTextureView(currentTextureView);
        }
        currentAspectRatioFrameLayout = aspectRatioFrameLayout;
        currentTextureViewContainer = container;
        if (currentAspectRatioFrameLayoutReady && currentAspectRatioFrameLayout != null) {
            currentAspectRatioFrameLayout.setAspectRatio(currentAspectRatioFrameLayoutRatio, currentAspectRatioFrameLayoutRotation);
            //if (currentTextureViewContainer.getVisibility() != View.VISIBLE) {
            //    currentTextureViewContainer.setVisibility(View.VISIBLE);
            //}
        }
    }

    public void setBaseActivity(Activity activity, boolean set) {
        if (set) {
            baseActivity = activity;
        } else if (baseActivity == activity) {
            baseActivity = null;
        }
    }

    public void setFeedbackView(View view, boolean set) {
        if (set) {
            feedbackView = view;
        } else if (feedbackView == view) {
            feedbackView = null;
        }
    }

    public void setPlaybackSpeed(boolean music, float speed) {
        if (music) {
            if (currentMusicPlaybackSpeed >= 6 && speed == 1f && playingMessageObject != null) {
                audioPlayer.pause();
                float p = playingMessageObject.audioProgress;
                final MessageObject currentMessage = playingMessageObject;
                AndroidUtilities.runOnUIThread(() -> {
                    if (audioPlayer != null && playingMessageObject != null && !isPaused) {
                        if (isSamePlayingMessage(currentMessage)) {
                            seekToProgress(playingMessageObject, p);
                        }
                        audioPlayer.play();
                    }
                }, 50);
            }
            currentMusicPlaybackSpeed = speed;
            if (Math.abs(speed - 1.0f) > 0.001f) {
                fastMusicPlaybackSpeed = speed;
            }
        } else {
            currentPlaybackSpeed = speed;
            if (Math.abs(speed - 1.0f) > 0.001f) {
                fastPlaybackSpeed = speed;
            }
        }
        if (audioPlayer != null) {
            audioPlayer.setPlaybackSpeed(speed);
        } else if (videoPlayer != null) {
            videoPlayer.setPlaybackSpeed(speed);
        }
        MessagesController.getGlobalMainSettings().edit()
                .putFloat(music ? "musicPlaybackSpeed" : "playbackSpeed", speed)
                .putFloat(music ? "fastMusicPlaybackSpeed" : "fastPlaybackSpeed", music ? fastMusicPlaybackSpeed : fastPlaybackSpeed).commit();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.messagePlayingSpeedChanged);
    }

    public float getPlaybackSpeed(boolean music) {
        return music ? currentMusicPlaybackSpeed : currentPlaybackSpeed;
    }

    public float getFastPlaybackSpeed(boolean music) {
        return music ? fastMusicPlaybackSpeed : fastPlaybackSpeed;
    }

    private void updateVideoState(MessageObject messageObject, int[] playCount, boolean destroyAtEnd, boolean playWhenReady, int playbackState) {
        if (videoPlayer == null) {
            return;
        }
        if (playbackState != ExoPlayer.STATE_ENDED && playbackState != ExoPlayer.STATE_IDLE) {
            try {
                baseActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Exception e) {
                FileLog.e(e);
            }
        } else {
            try {
                baseActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (playbackState == ExoPlayer.STATE_READY) {
            playerWasReady = true;
            if (playingMessageObject != null && (playingMessageObject.isVideo() || playingMessageObject.isRoundVideo())) {
                AndroidUtilities.cancelRunOnUIThread(setLoadingRunnable);
                FileLoader.getInstance(messageObject.currentAccount).removeLoadingVideo(playingMessageObject.getDocument(), true, false);
            }
            currentAspectRatioFrameLayoutReady = true;
        } else if (playbackState == ExoPlayer.STATE_BUFFERING) {
            if (playWhenReady && playingMessageObject != null && (playingMessageObject.isVideo() || playingMessageObject.isRoundVideo())) {
                if (playerWasReady) {
                    setLoadingRunnable.run();
                } else {
                    AndroidUtilities.runOnUIThread(setLoadingRunnable, 1000);
                }
            }
        } else if (videoPlayer.isPlaying() && playbackState == ExoPlayer.STATE_ENDED) {
            if (playingMessageObject.isVideo() && !destroyAtEnd && (playCount == null || playCount[0] < 4)) {
                videoPlayer.seekTo(0);
                if (playCount != null) {
                    playCount[0]++;
                }
            } else {
                cleanupPlayer(true, true, true, false);
            }
        }
    }

    public void injectVideoPlayer(VideoPlayer player, MessageObject messageObject) {
        if (player == null || messageObject == null) {
            return;
        }
        FileLoader.getInstance(messageObject.currentAccount).setLoadingVideoForPlayer(messageObject.getDocument(), true);
        playerWasReady = false;
        boolean destroyAtEnd = true;
        int[] playCount = null;
        clearPlaylist();
        videoPlayer = player;
        playingMessageObject = messageObject;
        int tag = ++playerNum;
        videoPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            @Override
            public void onStateChanged(boolean playWhenReady, int playbackState) {
                if (tag != playerNum) {
                    return;
                }
                updateVideoState(messageObject, playCount, destroyAtEnd, playWhenReady, playbackState);
            }

            @Override
            public void onError(VideoPlayer player, Exception e) {
                FileLog.e(e);
            }

            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                currentAspectRatioFrameLayoutRotation = unappliedRotationDegrees;
                if (unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270) {
                    int temp = width;
                    width = height;
                    height = temp;
                }
                currentAspectRatioFrameLayoutRatio = height == 0 ? 1 : (width * pixelWidthHeightRatio) / height;

                if (currentAspectRatioFrameLayout != null) {
                    currentAspectRatioFrameLayout.setAspectRatio(currentAspectRatioFrameLayoutRatio, currentAspectRatioFrameLayoutRotation);
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                if (currentAspectRatioFrameLayout != null && !currentAspectRatioFrameLayout.isDrawingReady()) {
                    isDrawingWasReady = true;
                    currentAspectRatioFrameLayout.setDrawingReady(true);
                    currentTextureViewContainer.setTag(1);
                }
            }

            @Override
            public boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture) {
                if (videoPlayer == null) {
                    return false;
                }
                if (pipSwitchingState == 2) {
                    if (currentAspectRatioFrameLayout != null) {
                        if (isDrawingWasReady) {
                            currentAspectRatioFrameLayout.setDrawingReady(true);
                        }
                        if (currentAspectRatioFrameLayout.getParent() == null) {
                            currentTextureViewContainer.addView(currentAspectRatioFrameLayout);
                        }
                        if (currentTextureView.getSurfaceTexture() != surfaceTexture) {
                            currentTextureView.setSurfaceTexture(surfaceTexture);
                        }
                        videoPlayer.setTextureView(currentTextureView);
                    }
                    pipSwitchingState = 0;
                    return true;
                } else if (pipSwitchingState == 1) {
                    if (baseActivity != null) {
                        if (pipRoundVideoView == null) {
                            try {
                                pipRoundVideoView = new PipRoundVideoView();
                                pipRoundVideoView.show(baseActivity, () -> cleanupPlayer(true, true));
                            } catch (Exception e) {
                                pipRoundVideoView = null;
                            }
                        }
                        if (pipRoundVideoView != null) {
                            if (pipRoundVideoView.getTextureView().getSurfaceTexture() != surfaceTexture) {
                                pipRoundVideoView.getTextureView().setSurfaceTexture(surfaceTexture);
                            }
                            videoPlayer.setTextureView(pipRoundVideoView.getTextureView());
                        }
                    }
                    pipSwitchingState = 0;
                    return true;
                } else if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isInjectingVideoPlayer()) {
                    PhotoViewer.getInstance().injectVideoPlayerSurface(surfaceTexture);
                    return true;
                }
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

            }
        });
        currentAspectRatioFrameLayoutReady = false;
        if (currentTextureView != null) {
            videoPlayer.setTextureView(currentTextureView);
        }

        checkAudioFocus(messageObject);
        setPlayerVolume();

        isPaused = false;
        lastProgress = 0;
        playingMessageObject = messageObject;
        if (!SharedConfig.raiseToSpeak) {
            startRaiseToEarSensors(raiseChat);
        }
        startProgressTimer(playingMessageObject);
        NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingDidStart, messageObject);

        /*try {
            if (playingMessageObject.audioProgress != 0) {
                long duration = videoPlayer.getDuration();
                if (duration == C.TIME_UNSET) {
                    duration = (long) playingMessageObject.getDuration() * 1000;
                }
                int seekTo = (int) (duration * playingMessageObject.audioProgress);
                if (playingMessageObject.audioProgressMs != 0) {
                    seekTo = playingMessageObject.audioProgressMs;
                    playingMessageObject.audioProgressMs = 0;
                }
                videoPlayer.seekTo(seekTo);
            }
        } catch (Exception e2) {
            playingMessageObject.audioProgress = 0;
            playingMessageObject.audioProgressSec = 0;
            NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, playingMessageObject.getId(), 0);
            FileLog.e(e2);
        }*/
    }

    public void playEmojiSound(AccountInstance accountInstance, String emoji, MessagesController.EmojiSound sound, boolean loadOnly) {
        if (sound == null) {
            return;
        }
        Utilities.stageQueue.postRunnable(() -> {
            TLRPC.Document document = new TLRPC.TL_document();
            document.access_hash = sound.accessHash;
            document.id = sound.id;
            document.mime_type = "sound/ogg";
            document.file_reference = sound.fileReference;
            document.dc_id = accountInstance.getConnectionsManager().getCurrentDatacenterId();
            File file = FileLoader.getInstance(accountInstance.getCurrentAccount()).getPathToAttach(document, true);
            if (file.exists()) {
                if (loadOnly) {
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        int tag = ++emojiSoundPlayerNum;
                        if (emojiSoundPlayer != null) {
                            emojiSoundPlayer.releasePlayer(true);
                        }
                        emojiSoundPlayer = new VideoPlayer(false);
                        emojiSoundPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                            @Override
                            public void onStateChanged(boolean playWhenReady, int playbackState) {
                                AndroidUtilities.runOnUIThread(() -> {
                                    if (tag != emojiSoundPlayerNum) {
                                        return;
                                    }
                                    if (playbackState == ExoPlayer.STATE_ENDED) {
                                        if (emojiSoundPlayer != null) {
                                            try {
                                                emojiSoundPlayer.releasePlayer(true);
                                                emojiSoundPlayer = null;
                                            } catch (Exception e) {
                                                FileLog.e(e);
                                            }
                                        }
                                    }
                                });
                            }

                            @Override
                            public void onError(VideoPlayer player, Exception e) {

                            }

                            @Override
                            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {

                            }

                            @Override
                            public void onRenderedFirstFrame() {

                            }

                            @Override
                            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

                            }

                            @Override
                            public boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture) {
                                return false;
                            }
                        });
                        emojiSoundPlayer.preparePlayer(Uri.fromFile(file), "other");
                        emojiSoundPlayer.setStreamType(AudioManager.STREAM_MUSIC);
                        emojiSoundPlayer.play();
                    } catch (Exception e) {
                        FileLog.e(e);
                        if (emojiSoundPlayer != null) {
                            emojiSoundPlayer.releasePlayer(true);
                            emojiSoundPlayer = null;
                        }
                    }
                });
            } else {
                AndroidUtilities.runOnUIThread(() -> accountInstance.getFileLoader().loadFile(document, null, FileLoader.PRIORITY_NORMAL, 1));
            }
        });
    }

    private static long volumeBarLastTimeShown;
    public void checkVolumeBarUI() {
        if (isSilent) {
            return;
        }
        try {
            final long now = System.currentTimeMillis();
            if (Math.abs(now - volumeBarLastTimeShown) < 5000) {
                return;
            }
            AudioManager audioManager = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
            int stream = useFrontSpeaker ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC;
            int volume = audioManager.getStreamVolume(stream);
            if (volume == 0) {
                audioManager.adjustStreamVolume(stream, volume, AudioManager.FLAG_SHOW_UI);
                volumeBarLastTimeShown = now;
            }
        } catch (Exception ignore) {}
    }

    private void setBluetoothScoOn(boolean scoOn) {
        AudioManager am = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
        if (am.isBluetoothScoAvailableOffCall() && SharedConfig.recordViaSco || !scoOn) {
            BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
            try {
                if (btAdapter != null && btAdapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED || !scoOn) {
                    if (scoOn && !am.isBluetoothScoOn()) {
                        am.startBluetoothSco();
                    } else if (!scoOn && am.isBluetoothScoOn()) {
                        am.stopBluetoothSco();
                    }
                }
            } catch (SecurityException ignored) {
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    public boolean playMessage(final MessageObject messageObject) {
        return playMessage(messageObject, false);
    }

    public boolean playMessage(final MessageObject messageObject, boolean silent) {
        if (messageObject == null) {
            return false;
        }
        isSilent = silent;
        checkVolumeBarUI();
        if ((audioPlayer != null || videoPlayer != null) && isSamePlayingMessage(messageObject)) {
            if (isPaused) {
                resumeAudio(messageObject);
            }
            if (!SharedConfig.raiseToSpeak) {
                startRaiseToEarSensors(raiseChat);
            }
            return true;
        }
        if (!messageObject.isOut() && messageObject.isContentUnread()) {
            MessagesController.getInstance(messageObject.currentAccount).markMessageContentAsRead(messageObject);
        }
        boolean notify = !playMusicAgain;
        if (playingMessageObject != null) {
            notify = false;
            if (!playMusicAgain) {
                playingMessageObject.resetPlayingProgress();
                NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, playingMessageObject.getId(), 0);
            }
        }
        cleanupPlayer(notify, false);
        shouldSavePositionForCurrentAudio = null;
        lastSaveTime = 0;
        playMusicAgain = false;
        seekToProgressPending = 0;
        File file = null;
        boolean exists = false;
        if (messageObject.messageOwner.attachPath != null && messageObject.messageOwner.attachPath.length() > 0) {
            file = new File(messageObject.messageOwner.attachPath);
            exists = file.exists();
            if (!exists) {
                file = null;
            }
        }
        final File cacheFile = file != null ? file : FileLoader.getInstance(messageObject.currentAccount).getPathToMessage(messageObject.messageOwner);
        boolean canStream = SharedConfig.streamMedia && (messageObject.isMusic() || messageObject.isRoundVideo() || messageObject.isVideo() && messageObject.canStreamVideo()) && !DialogObject.isEncryptedDialog(messageObject.getDialogId());
        if (cacheFile != file && !(exists = cacheFile.exists()) && !canStream) {
            FileLoader.getInstance(messageObject.currentAccount).loadFile(messageObject.getDocument(), messageObject, FileLoader.PRIORITY_LOW, 0);
            downloadingCurrentMessage = true;
            isPaused = false;
            lastProgress = 0;
            audioInfo = null;
            playingMessageObject = messageObject;
            if (playingMessageObject.isMusic()) {
                Intent intent = new Intent(ApplicationLoader.applicationContext, MusicPlayerService.class);
                try {
                    /*if (Build.VERSION.SDK_INT >= 26) {
                        ApplicationLoader.applicationContext.startForegroundService(intent);
                    } else {*/
                    ApplicationLoader.applicationContext.startService(intent);
                    //}
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            } else {
                Intent intent = new Intent(ApplicationLoader.applicationContext, MusicPlayerService.class);
                ApplicationLoader.applicationContext.stopService(intent);
            }
            NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingPlayStateChanged, playingMessageObject.getId());
            return true;
        } else {
            downloadingCurrentMessage = false;
        }
        if (messageObject.isMusic()) {
            checkIsNextMusicFileDownloaded(messageObject.currentAccount);
        } else {
            checkIsNextVoiceFileDownloaded(messageObject.currentAccount);
        }
        if (currentAspectRatioFrameLayout != null) {
            isDrawingWasReady = false;
            currentAspectRatioFrameLayout.setDrawingReady(false);
        }
        boolean isVideo = messageObject.isVideo();
        if (messageObject.isRoundVideo() || isVideo) {
            FileLoader.getInstance(messageObject.currentAccount).setLoadingVideoForPlayer(messageObject.getDocument(), true);
            playerWasReady = false;
            boolean destroyAtEnd = !isVideo || messageObject.messageOwner.peer_id.channel_id == 0 && messageObject.audioProgress <= 0.1f;
            int[] playCount = isVideo && messageObject.getDuration() <= 30 ? new int[]{1} : null;
            clearPlaylist();
            videoPlayer = new VideoPlayer();
            videoPlayer.setLooping(silent);
            int tag = ++playerNum;
            videoPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                @Override
                public void onStateChanged(boolean playWhenReady, int playbackState) {
                    if (tag != playerNum) {
                        return;
                    }
                    updateVideoState(messageObject, playCount, destroyAtEnd, playWhenReady, playbackState);
                }

                @Override
                public void onError(VideoPlayer player, Exception e) {
                    FileLog.e(e);
                }

                @Override
                public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                    currentAspectRatioFrameLayoutRotation = unappliedRotationDegrees;
                    if (unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270) {
                        int temp = width;
                        width = height;
                        height = temp;
                    }
                    currentAspectRatioFrameLayoutRatio = height == 0 ? 1 : (width * pixelWidthHeightRatio) / height;

                    if (currentAspectRatioFrameLayout != null) {
                        currentAspectRatioFrameLayout.setAspectRatio(currentAspectRatioFrameLayoutRatio, currentAspectRatioFrameLayoutRotation);
                    }
                }

                @Override
                public void onRenderedFirstFrame() {
                    if (currentAspectRatioFrameLayout != null && !currentAspectRatioFrameLayout.isDrawingReady()) {
                        isDrawingWasReady = true;
                        currentAspectRatioFrameLayout.setDrawingReady(true);
                        currentTextureViewContainer.setTag(1);
                        //if (currentTextureViewContainer != null && currentTextureViewContainer.getVisibility() != View.VISIBLE) {
                        //    currentTextureViewContainer.setVisibility(View.VISIBLE);
                        //}
                    }
                }

                @Override
                public boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture) {
                    if (videoPlayer == null) {
                        return false;
                    }
                    if (pipSwitchingState == 2) {
                        if (currentAspectRatioFrameLayout != null) {
                            if (isDrawingWasReady) {
                                currentAspectRatioFrameLayout.setDrawingReady(true);
                            }
                            if (currentAspectRatioFrameLayout.getParent() == null) {
                                currentTextureViewContainer.addView(currentAspectRatioFrameLayout);
                            }
                            if (currentTextureView.getSurfaceTexture() != surfaceTexture) {
                                currentTextureView.setSurfaceTexture(surfaceTexture);
                            }
                            videoPlayer.setTextureView(currentTextureView);
                        }
                        pipSwitchingState = 0;
                        return true;
                    } else if (pipSwitchingState == 1) {
                        if (baseActivity != null) {
                            if (pipRoundVideoView == null) {
                                try {
                                    pipRoundVideoView = new PipRoundVideoView();
                                    pipRoundVideoView.show(baseActivity, () -> cleanupPlayer(true, true));
                                } catch (Exception e) {
                                    pipRoundVideoView = null;
                                }
                            }
                            if (pipRoundVideoView != null) {
                                if (pipRoundVideoView.getTextureView().getSurfaceTexture() != surfaceTexture) {
                                    pipRoundVideoView.getTextureView().setSurfaceTexture(surfaceTexture);
                                }
                                videoPlayer.setTextureView(pipRoundVideoView.getTextureView());
                            }
                        }
                        pipSwitchingState = 0;
                        return true;
                    } else if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isInjectingVideoPlayer()) {
                        PhotoViewer.getInstance().injectVideoPlayerSurface(surfaceTexture);
                        return true;
                    }
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

                }
            });
            currentAspectRatioFrameLayoutReady = false;
            if (pipRoundVideoView != null || !MessagesController.getInstance(messageObject.currentAccount).isDialogVisible(messageObject.getDialogId(), messageObject.scheduled)) {
                if (pipRoundVideoView == null) {
                    try {
                        pipRoundVideoView = new PipRoundVideoView();
                        pipRoundVideoView.show(baseActivity, () -> cleanupPlayer(true, true));
                    } catch (Exception e) {
                        pipRoundVideoView = null;
                    }
                }
                if (pipRoundVideoView != null) {
                    videoPlayer.setTextureView(pipRoundVideoView.getTextureView());
                }
            } else if (currentTextureView != null) {
                videoPlayer.setTextureView(currentTextureView);
            }

            if (exists) {
                if (!messageObject.mediaExists && cacheFile != file) {
                    AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.fileLoaded, FileLoader.getAttachFileName(messageObject.getDocument()), cacheFile));
                }
                videoPlayer.preparePlayer(Uri.fromFile(cacheFile), "other");
            } else {
                try {
                    int reference = FileLoader.getInstance(messageObject.currentAccount).getFileReference(messageObject);
                    TLRPC.Document document = messageObject.getDocument();
                    String params = "?account=" + messageObject.currentAccount +
                            "&id=" + document.id +
                            "&hash=" + document.access_hash +
                            "&dc=" + document.dc_id +
                            "&size=" + document.size +
                            "&mime=" + URLEncoder.encode(document.mime_type, "UTF-8") +
                            "&rid=" + reference +
                            "&name=" + URLEncoder.encode(FileLoader.getDocumentFileName(document), "UTF-8") +
                            "&reference=" + Utilities.bytesToHex(document.file_reference != null ? document.file_reference : new byte[0]);
                    Uri uri = Uri.parse("tg://" + messageObject.getFileName() + params);
                    videoPlayer.preparePlayer(uri, "other");
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            if (messageObject.isRoundVideo()) {
                videoPlayer.setStreamType(useFrontSpeaker ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
                if (Math.abs(currentPlaybackSpeed - 1.0f) > 0.001f) {
                    videoPlayer.setPlaybackSpeed(currentPlaybackSpeed);
                }

                if (messageObject.forceSeekTo >= 0) {
                    messageObject.audioProgress = seekToProgressPending = messageObject.forceSeekTo;
                    messageObject.forceSeekTo = -1;
                }
            } else {
                videoPlayer.setStreamType(AudioManager.STREAM_MUSIC);
            }
        } else {
            if (pipRoundVideoView != null) {
                pipRoundVideoView.close(true);
                pipRoundVideoView = null;
            }
            try {
                audioPlayer = new VideoPlayer();
                int tag = ++playerNum;
                audioPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                    @Override
                    public void onStateChanged(boolean playWhenReady, int playbackState) {
                        if (tag != playerNum) {
                            return;
                        }
                        if (playbackState == ExoPlayer.STATE_ENDED || (playbackState == ExoPlayer.STATE_IDLE || playbackState == ExoPlayer.STATE_BUFFERING) && playWhenReady && messageObject.audioProgress >= 0.999f) {
                            messageObject.audioProgress = 1f;
                            NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, messageObject.getId(), 0);
                            if (!playlist.isEmpty() && (playlist.size() > 1 || !messageObject.isVoice())) {
                                playNextMessageWithoutOrder(true);
                            } else {
                                cleanupPlayer(true, true, messageObject.isVoice(), false);
                            }
                        } else if (audioPlayer != null && seekToProgressPending != 0 && (playbackState == ExoPlayer.STATE_READY || playbackState == ExoPlayer.STATE_IDLE)) {
                            int seekTo = (int) (audioPlayer.getDuration() * seekToProgressPending);
                            audioPlayer.seekTo(seekTo);
                            lastProgress = seekTo;
                            seekToProgressPending = 0;
                        }
                    }

                    @Override
                    public void onError(VideoPlayer player, Exception e) {

                    }

                    @Override
                    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {

                    }

                    @Override
                    public void onRenderedFirstFrame() {

                    }

                    @Override
                    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

                    }

                    @Override
                    public boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture) {
                        return false;
                    }
                });
                audioPlayer.setAudioVisualizerDelegate(new VideoPlayer.AudioVisualizerDelegate() {
                    @Override
                    public void onVisualizerUpdate(boolean playing, boolean animate, float[] values) {
                        Theme.getCurrentAudiVisualizerDrawable().setWaveform(playing, animate, values);
                    }

                    @Override
                    public boolean needUpdate() {
                        return Theme.getCurrentAudiVisualizerDrawable().getParentView() != null;
                    }
                });
                if (exists) {
                    if (!messageObject.mediaExists && cacheFile != file) {
                        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.fileLoaded, FileLoader.getAttachFileName(messageObject.getDocument()), cacheFile));
                    }
                    audioPlayer.preparePlayer(Uri.fromFile(cacheFile), "other");
                    isStreamingCurrentAudio = false;
                } else {
                    int reference = FileLoader.getInstance(messageObject.currentAccount).getFileReference(messageObject);
                    TLRPC.Document document = messageObject.getDocument();
                    String params = "?account=" + messageObject.currentAccount +
                            "&id=" + document.id +
                            "&hash=" + document.access_hash +
                            "&dc=" + document.dc_id +
                            "&size=" + document.size +
                            "&mime=" + URLEncoder.encode(document.mime_type, "UTF-8") +
                            "&rid=" + reference +
                            "&name=" + URLEncoder.encode(FileLoader.getDocumentFileName(document), "UTF-8") +
                            "&reference=" + Utilities.bytesToHex(document.file_reference != null ? document.file_reference : new byte[0]);
                    Uri uri = Uri.parse("tg://" + messageObject.getFileName() + params);
                    audioPlayer.preparePlayer(uri, "other");
                    isStreamingCurrentAudio = true;
                }
                if (messageObject.isVoice()) {
                    String name = messageObject.getFileName();
                    if (name != null && messageObject.getDuration() >= 5 * 60) {
                        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("media_saved_pos", Activity.MODE_PRIVATE);
                        float pos = preferences.getFloat(name, -1);
                        if (pos > 0 && pos < 0.99f) {
                            messageObject.audioProgress = seekToProgressPending = pos;
                        }
                        shouldSavePositionForCurrentAudio = name;
                    }
                    if (Math.abs(currentPlaybackSpeed - 1.0f) > 0.001f) {
                        audioPlayer.setPlaybackSpeed(currentPlaybackSpeed);
                    }
                    audioInfo = null;
                    clearPlaylist();
                } else {
                    try {
                        audioInfo = AudioInfo.getAudioInfo(cacheFile);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    String name = messageObject.getFileName();
                    if (!TextUtils.isEmpty(name) && messageObject.getDuration() >= 10 * 60) {
                        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("media_saved_pos", Activity.MODE_PRIVATE);
                        float pos = preferences.getFloat(name, -1);
                        if (pos > 0 && pos < 0.999f) {
                            messageObject.audioProgress = seekToProgressPending = pos;
                        }
                        shouldSavePositionForCurrentAudio = name;
                        if (Math.abs(currentMusicPlaybackSpeed - 1.0f) > 0.001f) {
                            audioPlayer.setPlaybackSpeed(currentMusicPlaybackSpeed);
                        }
                    }
                }
                if (messageObject.forceSeekTo >= 0) {
                    messageObject.audioProgress = seekToProgressPending = messageObject.forceSeekTo;
                    messageObject.forceSeekTo = -1;
                }
                audioPlayer.setStreamType(useFrontSpeaker ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
                audioPlayer.play();
                if (!messageObject.isVoice()) {
                    if (audioVolumeAnimator != null) {
                        audioVolumeAnimator.removeAllListeners();
                        audioVolumeAnimator.cancel();
                    }
                    audioVolumeAnimator = ValueAnimator.ofFloat(audioVolume, 1f);
                    audioVolumeAnimator.addUpdateListener(audioVolumeUpdateListener);
                    audioVolumeAnimator.setDuration(300);
                    audioVolumeAnimator.start();
                } else {
                    audioVolume = 1f;
                    setPlayerVolume();
                }
            } catch (Exception e) {
                FileLog.e(e);
                NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingPlayStateChanged, playingMessageObject != null ? playingMessageObject.getId() : 0);
                if (audioPlayer != null) {
                    audioPlayer.releasePlayer(true);
                    audioPlayer = null;
                    Theme.unrefAudioVisualizeDrawable(playingMessageObject);
                    isPaused = false;
                    playingMessageObject = null;
                    downloadingCurrentMessage = false;
                }
                return false;
            }
        }
        checkAudioFocus(messageObject);
        setPlayerVolume();

        isPaused = false;
        lastProgress = 0;
        playingMessageObject = messageObject;
        if (!SharedConfig.raiseToSpeak) {
            startRaiseToEarSensors(raiseChat);
        }
        if (!ApplicationLoader.mainInterfacePaused && proximityWakeLock != null && !proximityWakeLock.isHeld() && (playingMessageObject.isVoice() || playingMessageObject.isRoundVideo())) {
            proximityWakeLock.acquire();
        }
        startProgressTimer(playingMessageObject);
        NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingDidStart, messageObject);

        if (videoPlayer != null) {
            try {
                if (playingMessageObject.audioProgress != 0) {
                    long duration = videoPlayer.getDuration();
                    if (duration == C.TIME_UNSET) {
                        duration = (long) playingMessageObject.getDuration() * 1000;
                    }
                    int seekTo = (int) (duration * playingMessageObject.audioProgress);
                    if (playingMessageObject.audioProgressMs != 0) {
                        seekTo = playingMessageObject.audioProgressMs;
                        playingMessageObject.audioProgressMs = 0;
                    }
                    videoPlayer.seekTo(seekTo);
                }
            } catch (Exception e2) {
                playingMessageObject.audioProgress = 0;
                playingMessageObject.audioProgressSec = 0;
                NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, playingMessageObject.getId(), 0);
                FileLog.e(e2);
            }
            videoPlayer.play();
        } else if (audioPlayer != null) {
            try {
                if (playingMessageObject.audioProgress != 0) {
                    long duration = audioPlayer.getDuration();
                    if (duration == C.TIME_UNSET) {
                        duration = (long) playingMessageObject.getDuration() * 1000;
                    }
                    int seekTo = (int) (duration * playingMessageObject.audioProgress);
                    audioPlayer.seekTo(seekTo);
                }
            } catch (Exception e2) {
                playingMessageObject.resetPlayingProgress();
                NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, playingMessageObject.getId(), 0);
                FileLog.e(e2);
            }
        }

        if (playingMessageObject != null && playingMessageObject.isMusic()) {
            Intent intent = new Intent(ApplicationLoader.applicationContext, MusicPlayerService.class);
            try {
                /*if (Build.VERSION.SDK_INT >= 26) {
                    ApplicationLoader.applicationContext.startForegroundService(intent);
                } else {*/
                ApplicationLoader.applicationContext.startService(intent);
                //}
            } catch (Throwable e) {
                FileLog.e(e);
            }
        } else {
            Intent intent = new Intent(ApplicationLoader.applicationContext, MusicPlayerService.class);
            ApplicationLoader.applicationContext.stopService(intent);
        }

        return true;
    }
    
    public void updateSilent(boolean value) {
        isSilent = value;
        if (videoPlayer != null) {
            videoPlayer.setLooping(value);
        }
        setPlayerVolume();
        checkVolumeBarUI();
        if (playingMessageObject != null) {
            NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingPlayStateChanged, playingMessageObject != null ? playingMessageObject.getId() : 0);
        }
    }

    public AudioInfo getAudioInfo() {
        return audioInfo;
    }

    public void setPlaybackOrderType(int type) {
        boolean oldShuffle = SharedConfig.shuffleMusic;
        SharedConfig.setPlaybackOrderType(type);
        if (oldShuffle != SharedConfig.shuffleMusic) {
            if (SharedConfig.shuffleMusic) {
                buildShuffledPlayList();
            } else {
                if (playingMessageObject != null) {
                    currentPlaylistNum = playlist.indexOf(playingMessageObject);
                    if (currentPlaylistNum == -1) {
                        clearPlaylist();
                        cleanupPlayer(true, true);
                    }
                }
            }
        }
    }

    public boolean isStreamingCurrentAudio() {
        return isStreamingCurrentAudio;
    }

    public boolean isCurrentPlayer(VideoPlayer player) {
        return videoPlayer == player || audioPlayer == player;
    }

    public boolean pauseMessage(MessageObject messageObject) {
        if (audioPlayer == null && videoPlayer == null || messageObject == null || playingMessageObject == null || !isSamePlayingMessage(messageObject)) {
            return false;
        }
        stopProgressTimer();
        try {
            if (audioPlayer != null) {
                if (!playingMessageObject.isVoice() && (playingMessageObject.getDuration() * (1f - playingMessageObject.audioProgress) > 1)) {
                    if (audioVolumeAnimator != null) {
                        audioVolumeAnimator.removeAllUpdateListeners();
                        audioVolumeAnimator.cancel();
                    }
                    audioVolumeAnimator = ValueAnimator.ofFloat(1f, 0);
                    audioVolumeAnimator.addUpdateListener(audioVolumeUpdateListener);
                    audioVolumeAnimator.setDuration(300);
                    audioVolumeAnimator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (audioPlayer != null) {
                                audioPlayer.pause();
                            }
                        }
                    });
                    audioVolumeAnimator.start();
                } else {
                    audioPlayer.pause();
                }
            } else if (videoPlayer != null) {
                videoPlayer.pause();
            }
            isPaused = true;
            NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingPlayStateChanged, playingMessageObject.getId());
        } catch (Exception e) {
            FileLog.e(e);
            isPaused = false;
            return false;
        }
        return true;
    }

    private boolean resumeAudio(MessageObject messageObject) {
        if (audioPlayer == null && videoPlayer == null || messageObject == null || playingMessageObject == null || !isSamePlayingMessage(messageObject)) {
            return false;
        }

        try {
            startProgressTimer(playingMessageObject);
            if (audioVolumeAnimator != null) {
                audioVolumeAnimator.removeAllListeners();
                audioVolumeAnimator.cancel();
            }
            if (!messageObject.isVoice() && !messageObject.isRoundVideo()) {
                audioVolumeAnimator = ValueAnimator.ofFloat(audioVolume, 1f);
                audioVolumeAnimator.addUpdateListener(audioVolumeUpdateListener);
                audioVolumeAnimator.setDuration(300);
                audioVolumeAnimator.start();
            } else {
                audioVolume = 1f;
                setPlayerVolume();
            }
            if (audioPlayer != null) {
                audioPlayer.play();
            } else if (videoPlayer != null) {
                videoPlayer.play();
            }
            checkAudioFocus(messageObject);
            isPaused = false;
            NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingPlayStateChanged, playingMessageObject.getId());
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
        return true;
    }

    public boolean isVideoDrawingReady() {
        return currentAspectRatioFrameLayout != null && currentAspectRatioFrameLayout.isDrawingReady();
    }

    public ArrayList<MessageObject> getPlaylist() {
        return playlist;
    }

    public boolean isPlayingMessage(MessageObject messageObject) {
        if (audioPlayer == null && videoPlayer == null || messageObject == null || playingMessageObject == null) {
            return false;
        }
        if (playingMessageObject.eventId != 0 && playingMessageObject.eventId == messageObject.eventId) {
            return !downloadingCurrentMessage;
        }
        if (isSamePlayingMessage(messageObject)) {
            return !downloadingCurrentMessage;
        }
        //
        return false;
    }

    public boolean isPlayingMessageAndReadyToDraw(MessageObject messageObject) {
        return isDrawingWasReady && isPlayingMessage(messageObject);
    }

    public boolean isMessagePaused() {
        return isPaused || downloadingCurrentMessage;
    }

    public boolean isDownloadingCurrentMessage() {
        return downloadingCurrentMessage;
    }

    public void setReplyingMessage(MessageObject replyToMsg, MessageObject replyToTopMsg) {
        recordReplyingMsg = replyToMsg;
        recordReplyingTopMsg = replyToTopMsg;
    }

    public void requestAudioFocus(boolean request) {
        if (request) {
            if (!hasRecordAudioFocus && SharedConfig.pauseMusicOnRecord) {
                int result = NotificationsController.audioManager.requestAudioFocus(audioRecordFocusChangedListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    hasRecordAudioFocus = true;
                }
            }
        } else {
            if (hasRecordAudioFocus) {
                NotificationsController.audioManager.abandonAudioFocus(audioRecordFocusChangedListener);
                hasRecordAudioFocus = false;
            }
        }
    }

    public void startRecording(int currentAccount, long dialogId, MessageObject replyToMsg, MessageObject replyToTopMsg, int guid) {
        boolean paused = false;
        if (playingMessageObject != null && isPlayingMessage(playingMessageObject) && !isMessagePaused()) {
            paused = true;
        }

        requestAudioFocus(true);

        if (!NekoConfig.disableVibration.Bool()) {
            try {
                feedbackView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) {
            }
        }

        recordQueue.postRunnable(recordStartRunnable = () -> {
            if (audioRecorder != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    recordStartRunnable = null;
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStartError, guid);
                });
                return;
            }

            setBluetoothScoOn(true);

            sendAfterDone = 0;
            recordingAudio = new TLRPC.TL_document();
            recordingGuid = guid;
            recordingAudio.file_reference = new byte[0];
            recordingAudio.dc_id = Integer.MIN_VALUE;
            recordingAudio.id = SharedConfig.getLastLocalId();
            recordingAudio.user_id = UserConfig.getInstance(currentAccount).getClientUserId();
            recordingAudio.mime_type = "audio/ogg";
            recordingAudio.file_reference = new byte[0];
            SharedConfig.saveConfig();

            recordingAudioFile = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), FileLoader.getAttachFileName(recordingAudio));

            try {
                if (startRecord(recordingAudioFile.getAbsolutePath(), sampleRate) == 0) {
                    AndroidUtilities.runOnUIThread(() -> {
                        recordStartRunnable = null;
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStartError, guid);
                    });
                    return;
                }

                audioRecorder = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recordBufferSize);
                recordStartTime = System.currentTimeMillis();
                recordTimeCount = 0;
                samplesCount = 0;
                recordDialogId = dialogId;
                recordingCurrentAccount = currentAccount;
                recordReplyingMsg = replyToMsg;
                recordReplyingTopMsg = replyToTopMsg;
                fileBuffer.rewind();

                audioRecorder.startRecording();
            } catch (Exception e) {
                FileLog.e(e);
                recordingAudio = null;
                stopRecord();
                recordingAudioFile.delete();
                recordingAudioFile = null;
                try {
                    audioRecorder.release();
                    audioRecorder = null;
                } catch (Exception e2) {
                    FileLog.e(e2);
                }
                setBluetoothScoOn(false);

                AndroidUtilities.runOnUIThread(() -> {
                    recordStartRunnable = null;
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStartError, guid);
                });
                return;
            }

            recordQueue.postRunnable(recordRunnable);
            AndroidUtilities.runOnUIThread(() -> {
                recordStartRunnable = null;
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStarted, guid, true);
            });
        }, paused ? 500 : 50);
    }

    public void generateWaveform(MessageObject messageObject) {
        final String id = messageObject.getId() + "_" + messageObject.getDialogId();
        final String path = FileLoader.getInstance(messageObject.currentAccount).getPathToMessage(messageObject.messageOwner).getAbsolutePath();
        if (generatingWaveform.containsKey(id)) {
            return;
        }
        generatingWaveform.put(id, messageObject);
        Utilities.globalQueue.postRunnable(() -> {
            final byte[] waveform = getWaveform(path);
            AndroidUtilities.runOnUIThread(() -> {
                MessageObject messageObject1 = generatingWaveform.remove(id);
                if (messageObject1 == null) {
                    return;
                }
                if (waveform != null && messageObject1.getDocument() != null) {
                    for (int a = 0; a < messageObject1.getDocument().attributes.size(); a++) {
                        TLRPC.DocumentAttribute attribute = messageObject1.getDocument().attributes.get(a);
                        if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                            attribute.waveform = waveform;
                            attribute.flags |= 4;
                            break;
                        }
                    }
                    TLRPC.TL_messages_messages messagesRes = new TLRPC.TL_messages_messages();
                    messagesRes.messages.add(messageObject1.messageOwner);
                    MessagesStorage.getInstance(messageObject1.currentAccount).putMessages(messagesRes, messageObject1.getDialogId(), -1, 0, false, messageObject.scheduled, 0);
                    ArrayList<MessageObject> arrayList = new ArrayList<>();
                    arrayList.add(messageObject1);
                    NotificationCenter.getInstance(messageObject1.currentAccount).postNotificationName(NotificationCenter.replaceMessagesObjects, messageObject1.getDialogId(), arrayList);
                }
            });
        });
    }

    private void stopRecordingInternal(final int send, boolean notify, int scheduleDate) {
        if (send != 0) {
            final TLRPC.TL_document audioToSend = recordingAudio;
            final File recordingAudioFileToSend = recordingAudioFile;
            fileEncodingQueue.postRunnable(() -> {
                stopRecord();
                AndroidUtilities.runOnUIThread(() -> {
                    audioToSend.date = ConnectionsManager.getInstance(recordingCurrentAccount).getCurrentTime();
                    audioToSend.size = (int) recordingAudioFileToSend.length();
                    TLRPC.TL_documentAttributeAudio attributeAudio = new TLRPC.TL_documentAttributeAudio();
                    attributeAudio.voice = true;
                    attributeAudio.waveform = getWaveform2(recordSamples, recordSamples.length);
                    if (attributeAudio.waveform != null) {
                        attributeAudio.flags |= 4;
                    }
                    long duration = recordTimeCount;
                    attributeAudio.duration = (int) (recordTimeCount / 1000);
                    audioToSend.attributes.add(attributeAudio);
                    if (duration > 700) {
                        NotificationCenter.getInstance(recordingCurrentAccount).postNotificationName(NotificationCenter.beforeAudioDidSent, recordingGuid, send == 2 ? audioToSend : null, send == 2 ? recordingAudioFileToSend.getAbsolutePath() : null);
                        if (send == 1) {
                            SendMessagesHelper.getInstance(recordingCurrentAccount).sendMessage(audioToSend, null, recordingAudioFileToSend.getAbsolutePath(), recordDialogId, recordReplyingMsg, recordReplyingTopMsg, null, null, null, null, notify, scheduleDate, 0, null, null, false);
                        }
                        NotificationCenter.getInstance(recordingCurrentAccount).postNotificationName(NotificationCenter.audioDidSent, recordingGuid, send == 2 ? audioToSend : null, send == 2 ? recordingAudioFileToSend.getAbsolutePath() : null);
                    } else {
                        NotificationCenter.getInstance(recordingCurrentAccount).postNotificationName(NotificationCenter.audioRecordTooShort, recordingGuid, false, (int) duration);
                        recordingAudioFileToSend.delete();
                    }
                    requestAudioFocus(false);
                });
            });
        } else {
            if (recordingAudioFile != null) {
                recordingAudioFile.delete();
            }
            requestAudioFocus(false);
        }
        try {
            if (audioRecorder != null) {
                audioRecorder.release();
                audioRecorder = null;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        recordingAudio = null;
        recordingAudioFile = null;
    }

    public void stopRecording(final int send, boolean notify, int scheduleDate) {
        if (recordStartRunnable != null) {
            recordQueue.cancelRunnable(recordStartRunnable);
            recordStartRunnable = null;
        }
        recordQueue.postRunnable(() -> {
            if (sendAfterDone == 3) {
                sendAfterDone = 0;
                stopRecordingInternal(send, notify, scheduleDate);
                return;
            }
            if (audioRecorder == null) {
                return;
            }
            try {
                sendAfterDone = send;
                sendAfterDoneNotify = notify;
                sendAfterDoneScheduleDate = scheduleDate;
                audioRecorder.stop();
                setBluetoothScoOn(false);
            } catch (Exception e) {
                FileLog.e(e);
                if (recordingAudioFile != null) {
                    recordingAudioFile.delete();
                }
            }
            if (send == 0) {
                stopRecordingInternal(0, false, 0);
            }
            if (!NekoConfig.disableVibration.Bool()) {
                try {
                    feedbackView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                } catch (Exception ignore) {

                }
            }
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(recordingCurrentAccount).postNotificationName(NotificationCenter.recordStopped, recordingGuid, send == 2 ? 1 : 0));
        });
    }

    private static class MediaLoader implements NotificationCenter.NotificationCenterDelegate {

        private AccountInstance currentAccount;
        private AlertDialog progressDialog;
        private ArrayList<MessageObject> messageObjects;
        private HashMap<String, MessageObject> loadingMessageObjects = new HashMap<>();
        private float finishedProgress;
        private boolean cancelled;
        private boolean finished;
        private int copiedFiles;
        private CountDownLatch waitingForFile;
        private MessagesStorage.IntCallback onFinishRunnable;
        private boolean isMusic;

        private final int notificationId;

        public MediaLoader(Context context, AccountInstance accountInstance, ArrayList<MessageObject> messages, MessagesStorage.IntCallback onFinish) {
            notificationId = SaveToDownloadReceiver.createNotificationId();
            currentAccount = accountInstance;
            messageObjects = messages;
            onFinishRunnable = onFinish;
            isMusic = messages.get(0).isMusic();
            currentAccount.getNotificationCenter().addObserver(this, NotificationCenter.fileLoaded);
            currentAccount.getNotificationCenter().addObserver(this, NotificationCenter.fileLoadProgressChanged);
            currentAccount.getNotificationCenter().addObserver(this, NotificationCenter.fileLoadFailed);
            progressDialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_LOADING);
            progressDialog.setMessage(LocaleController.getString("Loading", R.string.Loading));
            progressDialog.setCanceledOnTouchOutside(false);
            progressDialog.setCancelable(true);
            progressDialog.setOnCancelListener(d -> cancelled = true);
        }

        public void start(Context context) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!finished) {
                    SaveToDownloadReceiver.showNotification(context, notificationId, messageObjects.size(), () -> cancelled = true);
                }
            }, 250);

            new Thread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        for (int b = 0, N = messageObjects.size(); b < N; b++) {
                            MessageObject message = messageObjects.get(b);
                            String path = message.messageOwner.attachPath;
                            String name = message.getDocumentName();
                            if (path != null && path.length() > 0) {
                                File temp = new File(path);
                                if (!temp.exists()) {
                                    path = null;
                                }
                            }
                            if (path == null || path.length() == 0) {
                                path = FileLoader.getInstance(currentAccount.getCurrentAccount()).getPathToMessage(message.messageOwner).toString();
                            }
                            File sourceFile = new File(path);
                            if (!sourceFile.exists()) {
                                waitingForFile = new CountDownLatch(1);
                                addMessageToLoad(message);
                                waitingForFile.await();
                            }
                            if (cancelled) {
                                break;
                            }
                            if (sourceFile.exists()) {
                                saveFileInternal(isMusic ? 3 : 2, sourceFile, name);
                                copiedFiles++;
                            }
                        }
                    } else {
                        File dir;
                        if (isMusic) {
                            dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                        } else {
                            dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        }
                        dir.mkdir();
                        for (int b = 0, N = messageObjects.size(); b < N; b++) {
                            MessageObject message = messageObjects.get(b);
                            String name = message.getDocumentName();
                            File destFile = new File(dir, name);
                            if (destFile.exists()) {
                                int idx = name.lastIndexOf('.');
                                for (int a = 0; a < 10; a++) {
                                    String newName;
                                    if (idx != -1) {
                                        newName = name.substring(0, idx) + "(" + (a + 1) + ")" + name.substring(idx);
                                    } else {
                                        newName = name + "(" + (a + 1) + ")";
                                    }
                                    destFile = new File(dir, newName);
                                    if (!destFile.exists()) {
                                        break;
                                    }
                                }
                            }
                            if (!destFile.exists()) {
                                destFile.createNewFile();
                            }
                            String path = message.messageOwner.attachPath;
                            if (path != null && path.length() > 0) {
                                File temp = new File(path);
                                if (!temp.exists()) {
                                    path = null;
                                }
                            }
                            if (path == null || path.length() == 0) {
                                path = FileLoader.getInstance(currentAccount.getCurrentAccount()).getPathToMessage(message.messageOwner).toString();
                            }
                            File sourceFile = new File(path);
                            if (!sourceFile.exists()) {
                                waitingForFile = new CountDownLatch(1);
                                addMessageToLoad(message);
                                waitingForFile.await();
                            }
                            if (sourceFile.exists()) {
                                copyFile(sourceFile, destFile, message.getMimeType());
                                copiedFiles++;
                            }
                        }
                    }
                    checkIfFinished();
                } catch (Exception e) {
                    FileLog.e(e);
                }

            }).start();
        }

        private void checkIfFinished() {
            if (!loadingMessageObjects.isEmpty()) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    SaveToDownloadReceiver.cancelNotification(notificationId);
                    finished = true;
                    if (onFinishRunnable != null) {
                        AndroidUtilities.runOnUIThread(() -> onFinishRunnable.run(copiedFiles));
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
                currentAccount.getNotificationCenter().removeObserver(this, NotificationCenter.fileLoaded);
                currentAccount.getNotificationCenter().removeObserver(this, NotificationCenter.fileLoadProgressChanged);
                currentAccount.getNotificationCenter().removeObserver(this, NotificationCenter.fileLoadFailed);
            });
        }

        private void addMessageToLoad(MessageObject messageObject) {
            AndroidUtilities.runOnUIThread(() -> {
                TLRPC.Document document = messageObject.getDocument();
                if (document == null) {
                    return;
                }
                String fileName = FileLoader.getAttachFileName(document);
                loadingMessageObjects.put(fileName, messageObject);
                currentAccount.getFileLoader().loadFile(document, messageObject, FileLoader.PRIORITY_LOW, 0);
            });
        }

        private boolean copyFile(File sourceFile, File destFile, String mime) {
            if (AndroidUtilities.isInternalUri(Uri.fromFile(sourceFile))) {
                return false;
            }
            try (FileInputStream inputStream = new FileInputStream(sourceFile); FileChannel source = inputStream.getChannel(); FileChannel destination = new FileOutputStream(destFile).getChannel()) {
                long size = source.size();
                try {
                    @SuppressLint("DiscouragedPrivateApi") Method getInt = FileDescriptor.class.getDeclaredMethod("getInt$");
                    int fdint = (Integer) getInt.invoke(inputStream.getFD());
                    if (AndroidUtilities.isInternalUri(fdint)) {
                        AndroidUtilities.runOnUIThread(() -> SaveToDownloadReceiver.cancelNotification(notificationId));
                        return false;
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                long lastProgress = 0;
                for (long a = 0; a < size; a += 4096) {
                    if (cancelled) {
                        break;
                    }
                    destination.transferFrom(source, a, Math.min(4096, size - a));
                    if (a + 4096 >= size || lastProgress <= SystemClock.elapsedRealtime() - 500) {
                        lastProgress = SystemClock.elapsedRealtime();
                        final int progress = (int) (finishedProgress + 100.0f / messageObjects.size() * a / size);
                        AndroidUtilities.runOnUIThread(() -> SaveToDownloadReceiver.updateNotification(notificationId, progress));
                    }
                }
                if (!cancelled) {
                    if (isMusic) {
                        AndroidUtilities.addMediaToGallery(destFile);
                    } else {
                        DownloadManager downloadManager = (DownloadManager) ApplicationLoader.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE);
                        String mimeType = mime;
                        if (TextUtils.isEmpty(mimeType)) {
                            MimeTypeMap myMime = MimeTypeMap.getSingleton();
                            String name = destFile.getName();
                            int idx = name.lastIndexOf('.');
                            if (idx != -1) {
                                String ext = name.substring(idx + 1);
                                mimeType = myMime.getMimeTypeFromExtension(ext.toLowerCase());
                                if (TextUtils.isEmpty(mimeType)) {
                                    mimeType = "text/plain";
                                }
                            } else {
                                mimeType = "text/plain";
                            }
                        }
                        downloadManager.addCompletedDownload(destFile.getName(), destFile.getName(), false, mimeType, destFile.getAbsolutePath(), destFile.length(), true);
                    }
                    finishedProgress += 100.0f / messageObjects.size();
                    final int progress = (int) (finishedProgress);
                    AndroidUtilities.runOnUIThread(() -> SaveToDownloadReceiver.updateNotification(notificationId, progress));
                    return true;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            destFile.delete();
            return false;
        }

        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (id == NotificationCenter.fileLoaded || id == NotificationCenter.fileLoadFailed) {
                String fileName = (String) args[0];
                if (loadingMessageObjects.remove(fileName) != null) {
                    waitingForFile.countDown();
                }
            } else if (id == NotificationCenter.fileLoadProgressChanged) {
                String fileName = (String) args[0];
                if (loadingMessageObjects.containsKey(fileName)) {
                    Long loadedSize = (Long) args[1];
                    Long totalSize = (Long) args[2];
                    float loadProgress = loadedSize / (float) totalSize;
                    final int progress = (int) (finishedProgress + loadProgress / messageObjects.size() * 100);
                    AndroidUtilities.runOnUIThread(() -> SaveToDownloadReceiver.updateNotification(notificationId, progress));
                }
            }
        }
    }

    public static void saveFilesFromMessages(Context context, AccountInstance accountInstance, ArrayList<MessageObject> messageObjects, final MessagesStorage.IntCallback onSaved) {
        if (messageObjects == null || messageObjects.isEmpty()) {
            return;
        }
        new MediaLoader(context, accountInstance, messageObjects, onSaved).start(context);
    }

    public static void saveFile(String fullPath, Context context, final int type, final String name, final String mime) {
        saveFile(fullPath, context, type, name, mime, null);
    }

    public static void saveFile(String fullPath, Context context, final int type, final String name, final String mime, final Runnable onSaved) {
        if (fullPath == null || context == null) {
            return;
        }

        File file = null;
        if (!TextUtils.isEmpty(fullPath)) {
            file = new File(fullPath);
            if (!file.exists()) {
                file = null;
            }
        }

        if (file == null) {
            return;
        }

        final File sourceFile = file;
        final boolean[] cancelled = new boolean[]{false};
        if (sourceFile.exists()) {

            int notificationId = SaveToDownloadReceiver.createNotificationId();
            final boolean[] finished = new boolean[1];
            if (context != null && type != 0) {
                try {
                    final AlertDialog dialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_LOADING);
                    dialog.setMessage(LocaleController.getString("Loading", R.string.Loading));
                    dialog.setCanceledOnTouchOutside(false);
                    dialog.setCancelable(true);
                    dialog.setOnCancelListener(d -> cancelled[0] = true);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!finished[0]) {
                            SaveToDownloadReceiver.showNotification(context, notificationId, 1, () -> cancelled[0] = true);
                        }
                    }, 250);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }

            new Thread(() -> {
                try {

                    boolean result = true;
                    final String folderName = NekoConfig.customSavePath.String();
                    if (Build.VERSION.SDK_INT >= 29) {
                        result = saveFileInternal(type, sourceFile, name);
                    } else {
                        File destFile;
                        if (type == 0) {
                            destFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), folderName);
                            destFile.mkdirs();
                            destFile = new File(destFile, AndroidUtilities.generateFileName(0, FileLoader.getFileExtension(sourceFile)));
                        } else if (type == 1) {
                            destFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), folderName);
                            destFile.mkdirs();
                            destFile = new File(destFile, AndroidUtilities.generateFileName(1, FileLoader.getFileExtension(sourceFile)));
                        } else {
                            File dir;
                            if (type == 2) {
                                dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                            } else {
                                dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                            }
                            dir = new File(dir, folderName);
                            dir.mkdirs();
                            destFile = new File(dir, name);
                            if (destFile.exists()) {
                                int idx = name.lastIndexOf('.');
                                for (int a = 0; a < 10; a++) {
                                    String newName;
                                    if (idx != -1) {
                                        newName = name.substring(0, idx) + "(" + (a + 1) + ")" + name.substring(idx);
                                    } else {
                                        newName = name + "(" + (a + 1) + ")";
                                    }
                                    destFile = new File(dir, newName);
                                    if (!destFile.exists()) {
                                        break;
                                    }
                                }
                            }
                        }
                        if (!destFile.exists()) {
                            destFile.createNewFile();
                        }
                        long lastProgress = System.currentTimeMillis() - 500;
                        try (FileInputStream inputStream = new FileInputStream(sourceFile); FileChannel source = inputStream.getChannel(); FileChannel destination = new FileOutputStream(destFile).getChannel()) {
                            long size = source.size();
                            try {
                                @SuppressLint("DiscouragedPrivateApi") Method getInt = FileDescriptor.class.getDeclaredMethod("getInt$");
                                int fdint = (Integer) getInt.invoke(inputStream.getFD());
                                if (AndroidUtilities.isInternalUri(fdint)) {
                                    AndroidUtilities.runOnUIThread(() -> SaveToDownloadReceiver.cancelNotification(notificationId));
                                    return;
                                }
                            } catch (Throwable e) {
                                FileLog.e(e);
                            }
                            for (long a = 0; a < size; a += 4096) {
                                if (cancelled[0]) {
                                    break;
                                }
                                destination.transferFrom(source, a, Math.min(4096, size - a));
                                //if (finalProgress != null) {
                                    if (lastProgress <= System.currentTimeMillis() - 500) {
                                        lastProgress = System.currentTimeMillis();
                                        final int progress = (int) ((float) a / (float) size * 100);
                                        AndroidUtilities.runOnUIThread(() -> SaveToDownloadReceiver.updateNotification(notificationId, progress));
                                    }
                                //}
                            }
                        } catch (Exception e) {
                            FileLog.e(e);
                            result = false;
                        }
                        if (cancelled[0]) {
                            destFile.delete();
                            result = false;
                        }
                        if (result) {
                            if (type == 2) {
                                DownloadManager downloadManager = (DownloadManager) ApplicationLoader.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE);
                                downloadManager.addCompletedDownload(destFile.getName(), destFile.getName(), false, mime, destFile.getAbsolutePath(), destFile.length(), true);
                            } else {
                                AndroidUtilities.addMediaToGallery(destFile.getAbsoluteFile());
                            }
                        }
                    }
                    if (result && onSaved != null) {
                        AndroidUtilities.runOnUIThread(onSaved);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
                //if (finalProgress != null) {
                    AndroidUtilities.runOnUIThread(() -> {
                        try {
                            SaveToDownloadReceiver.cancelNotification(notificationId);
                            finished[0] = true;
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    });
                //}
            }).start();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private static boolean saveFileInternal(int type, File sourceFile, String filename) {
        try {
            int selectedType = type;
            ContentValues contentValues = new ContentValues();
            String extension = FileLoader.getFileExtension(sourceFile);
            String mimeType = null;
            if (extension != null) {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            }
            Uri uriToInsert = null;
            if ((type == 0 || type == 1) && mimeType != null) {
                if (mimeType.startsWith("image")) {
                    selectedType = 0;
                }
                if (mimeType.startsWith("video")) {
                    selectedType = 1;
                }
            }
            final String folderName = NekoConfig.customSavePath.String();
            if (selectedType == 0) {
                if (filename == null) {
                    filename = AndroidUtilities.generateFileName(0, extension);
                }
                uriToInsert = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                File dirDest = new File(Environment.DIRECTORY_PICTURES, folderName);
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, dirDest + File.separator);
                contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                contentValues.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
            } else if (selectedType == 1) {
                if (filename == null) {
                    filename = AndroidUtilities.generateFileName(1, extension);
                }
                File dirDest = new File(Environment.DIRECTORY_MOVIES, folderName);
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, dirDest + File.separator);
                uriToInsert = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
            } else if (selectedType == 2) {
                if (filename == null) {
                    filename = sourceFile.getName();
                }
                File dirDest = new File(Environment.DIRECTORY_DOWNLOADS, folderName);
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, dirDest + File.separator);
                uriToInsert = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                contentValues.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            } else {
                if (filename == null) {
                    filename = sourceFile.getName();
                }
                File dirDest = new File(Environment.DIRECTORY_MUSIC, folderName);
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, dirDest + File.separator);
                uriToInsert = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                contentValues.put(MediaStore.Audio.Media.DISPLAY_NAME, filename);
            }

            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);

            Uri dstUri = ApplicationLoader.applicationContext.getContentResolver().insert(uriToInsert, contentValues);
            if (dstUri != null) {
                FileInputStream fileInputStream = new FileInputStream(sourceFile);
                OutputStream outputStream = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(dstUri);
                AndroidUtilities.copyFile(fileInputStream, outputStream);
                fileInputStream.close();
            }
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public static String getStickerExt(Uri uri) {
        InputStream inputStream = null;
        try {
            try {
                inputStream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
            } catch (Exception e) {
                inputStream = null;
            }
            if (inputStream == null) {
                File file = new File(uri.getPath());
                if (file.exists()) {
                    inputStream = new FileInputStream(file);
                }
            }

            byte[] header = new byte[12];
            if (inputStream.read(header, 0, 12) == 12) {
                if (header[0] == (byte) 0x89 && header[1] == (byte) 0x50 && header[2] == (byte) 0x4E && header[3] == (byte) 0x47 && header[4] == (byte) 0x0D && header[5] == (byte) 0x0A && header[6] == (byte) 0x1A && header[7] == (byte) 0x0A) {
                    return "png";
                }
                if (header[0] == 0x1f && header[1] == (byte) 0x8b) {
                    return "tgs";
                }
                String str = new String(header);
                if (str != null) {
                    str = str.toLowerCase();
                    if (str.startsWith("riff") && str.endsWith("webp")) {
                        return "webp";
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception e2) {
                FileLog.e(e2);
            }
        }
        return null;
    }

    public static boolean isWebp(Uri uri) {
        InputStream inputStream = null;
        try {
            inputStream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
            byte[] header = new byte[12];
            if (inputStream.read(header, 0, 12) == 12) {
                String str = new String(header);
                str = str.toLowerCase();
                if (str.startsWith("riff") && str.endsWith("webp")) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception e2) {
                FileLog.e(e2);
            }
        }
        return false;
    }

    public static boolean isGif(Uri uri) {
        InputStream inputStream = null;
        try {
            inputStream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
            byte[] header = new byte[3];
            if (inputStream.read(header, 0, 3) == 3) {
                String str = new String(header);
                if (str.equalsIgnoreCase("gif")) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception e2) {
                FileLog.e(e2);
            }
        }
        return false;
    }

    public static String getFileName(Uri uri) {
        if (uri == null) {
            return "";
        }
        try {
            String result = null;
            if (uri.getScheme().equals("content")) {
                try (Cursor cursor = ApplicationLoader.applicationContext.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor.moveToFirst()) {
                        result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            if (result == null) {
                result = uri.getPath();
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            }
            return result;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "";
    }

    public static String copyFileToCache(Uri uri, String ext) {
        return copyFileToCache(uri, ext, -1);
    }

    @SuppressLint("DiscouragedPrivateApi")
    public static String copyFileToCache(Uri uri, String ext, long sizeLimit) {
        InputStream inputStream = null;
        FileOutputStream output = null;
        int totalLen = 0;
        File f = null;
        try {
            String name = FileLoader.fixFileName(getFileName(uri));
            if (name == null) {
                int id = SharedConfig.getLastLocalId();
                SharedConfig.saveConfig();
                name = String.format(Locale.US, "%d.%s", id, ext);
            }
            f = AndroidUtilities.getSharingDirectory();
            f.mkdirs();
            if (AndroidUtilities.isInternalUri(Uri.fromFile(f))) {
                return null;
            }
            int count = 0;
            do {
                f = AndroidUtilities.getSharingDirectory();
                if (count == 0) {
                    f = new File(f, name);
                } else {
                    int lastDotIndex = name.lastIndexOf(".");
                    if (lastDotIndex > 0) {
                        f = new File(f, name.substring(0, lastDotIndex) + " (" + count + ")" + name.substring(lastDotIndex));
                    } else {
                        f = new File(f, name + " (" + count + ")");
                    }
                }
                count++;
            } while (f.exists());
            inputStream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
            if (inputStream instanceof FileInputStream) {
                FileInputStream fileInputStream = (FileInputStream) inputStream;
                try {
                    Method getInt = FileDescriptor.class.getDeclaredMethod("getInt$");
                    int fdint = (Integer) getInt.invoke(fileInputStream.getFD());
                    if (AndroidUtilities.isInternalUri(fdint)) {
                        return null;
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            output = new FileOutputStream(f);
            byte[] buffer = new byte[1024 * 20];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, len);
                totalLen += len;
                if (sizeLimit > 0 && totalLen > sizeLimit) {
                    return null;
                }
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception e2) {
                FileLog.e(e2);
            }
            try {
                if (output != null) {
                    output.close();
                }
            } catch (Exception e2) {
                FileLog.e(e2);
            }
            if (sizeLimit > 0 && totalLen > sizeLimit) {
                f.delete();
            }
        }
        return null;
    }

    public static void loadGalleryPhotosAlbums(final int guid) {
        Thread thread = new Thread(() -> {
            final ArrayList<AlbumEntry> mediaAlbumsSorted = new ArrayList<>();
            final ArrayList<AlbumEntry> photoAlbumsSorted = new ArrayList<>();
            SparseArray<AlbumEntry> mediaAlbums = new SparseArray<>();
            SparseArray<AlbumEntry> photoAlbums = new SparseArray<>();
            AlbumEntry allPhotosAlbum = null;
            AlbumEntry allVideosAlbum = null;
            AlbumEntry allMediaAlbum = null;
            String cameraFolder = null;
            try {
                cameraFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath() + "/" + "Camera/";
            } catch (Exception e) {
                FileLog.e(e);
            }
            Integer mediaCameraAlbumId = null;
            Integer photoCameraAlbumId = null;

            Cursor cursor = null;
            try {
                if (Build.VERSION.SDK_INT < 23 || ApplicationLoader.applicationContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    cursor = MediaStore.Images.Media.query(ApplicationLoader.applicationContext.getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projectionPhotos, null, null, (Build.VERSION.SDK_INT > 28 ? MediaStore.Images.Media.DATE_MODIFIED : MediaStore.Images.Media.DATE_TAKEN) + " DESC");
                    if (cursor != null) {
                        int imageIdColumn = cursor.getColumnIndex(MediaStore.Images.Media._ID);
                        int bucketIdColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID);
                        int bucketNameColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                        int dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
                        int dateColumn = cursor.getColumnIndex(Build.VERSION.SDK_INT > 28 ? MediaStore.Images.Media.DATE_MODIFIED : MediaStore.Images.Media.DATE_TAKEN);
                        int orientationColumn = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION);
                        int widthColumn = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH);
                        int heightColumn = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT);
                        int sizeColumn = cursor.getColumnIndex(MediaStore.Images.Media.SIZE);

                        while (cursor.moveToNext()) {
                            String path = cursor.getString(dataColumn);
                            if (TextUtils.isEmpty(path)) {
                                continue;
                            }

                            int imageId = cursor.getInt(imageIdColumn);
                            int bucketId = cursor.getInt(bucketIdColumn);
                            String bucketName = cursor.getString(bucketNameColumn);
                            long dateTaken = cursor.getLong(dateColumn);
                            int orientation = cursor.getInt(orientationColumn);
                            int width = cursor.getInt(widthColumn);
                            int height = cursor.getInt(heightColumn);
                            long size = cursor.getLong(sizeColumn);

                            PhotoEntry photoEntry = new PhotoEntry(bucketId, imageId, dateTaken, path, orientation, false, width, height, size);

                            if (allPhotosAlbum == null) {
                                allPhotosAlbum = new AlbumEntry(0, LocaleController.getString("AllPhotos", R.string.AllPhotos), photoEntry);
                                photoAlbumsSorted.add(0, allPhotosAlbum);
                            }
                            if (allMediaAlbum == null) {
                                allMediaAlbum = new AlbumEntry(0, LocaleController.getString("AllMedia", R.string.AllMedia), photoEntry);
                                mediaAlbumsSorted.add(0, allMediaAlbum);
                            }
                            allPhotosAlbum.addPhoto(photoEntry);
                            allMediaAlbum.addPhoto(photoEntry);

                            AlbumEntry albumEntry = mediaAlbums.get(bucketId);
                            if (albumEntry == null) {
                                albumEntry = new AlbumEntry(bucketId, bucketName, photoEntry);
                                mediaAlbums.put(bucketId, albumEntry);
                                if (mediaCameraAlbumId == null && cameraFolder != null && path != null && path.startsWith(cameraFolder)) {
                                    mediaAlbumsSorted.add(0, albumEntry);
                                    mediaCameraAlbumId = bucketId;
                                } else {
                                    mediaAlbumsSorted.add(albumEntry);
                                }
                            }
                            albumEntry.addPhoto(photoEntry);

                            albumEntry = photoAlbums.get(bucketId);
                            if (albumEntry == null) {
                                albumEntry = new AlbumEntry(bucketId, bucketName, photoEntry);
                                photoAlbums.put(bucketId, albumEntry);
                                if (photoCameraAlbumId == null && cameraFolder != null && path != null && path.startsWith(cameraFolder)) {
                                    photoAlbumsSorted.add(0, albumEntry);
                                    photoCameraAlbumId = bucketId;
                                } else {
                                    photoAlbumsSorted.add(albumEntry);
                                }
                            }
                            albumEntry.addPhoto(photoEntry);
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }

            try {
                if (Build.VERSION.SDK_INT < 23 || ApplicationLoader.applicationContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    cursor = MediaStore.Images.Media.query(ApplicationLoader.applicationContext.getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projectionVideo, null, null, (Build.VERSION.SDK_INT > 28 ? MediaStore.Video.Media.DATE_MODIFIED : MediaStore.Video.Media.DATE_TAKEN) + " DESC");
                    if (cursor != null) {
                        int imageIdColumn = cursor.getColumnIndex(MediaStore.Video.Media._ID);
                        int bucketIdColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID);
                        int bucketNameColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                        int dataColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATA);
                        int dateColumn = cursor.getColumnIndex(Build.VERSION.SDK_INT > 28 ? MediaStore.Video.Media.DATE_MODIFIED : MediaStore.Video.Media.DATE_TAKEN);
                        int durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION);
                        int widthColumn = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH);
                        int heightColumn = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT);
                        int sizeColumn = cursor.getColumnIndex(MediaStore.Video.Media.SIZE);

                        while (cursor.moveToNext()) {
                            String path = cursor.getString(dataColumn);
                            if (TextUtils.isEmpty(path)) {
                                continue;
                            }

                            int imageId = cursor.getInt(imageIdColumn);
                            int bucketId = cursor.getInt(bucketIdColumn);
                            String bucketName = cursor.getString(bucketNameColumn);
                            long dateTaken = cursor.getLong(dateColumn);
                            long duration = cursor.getLong(durationColumn);
                            int width = cursor.getInt(widthColumn);
                            int height = cursor.getInt(heightColumn);
                            long size = cursor.getLong(sizeColumn);

                            PhotoEntry photoEntry = new PhotoEntry(bucketId, imageId, dateTaken, path, (int) (duration / 1000), true, width, height, size);

                            if (allVideosAlbum == null) {
                                allVideosAlbum = new AlbumEntry(0, LocaleController.getString("AllVideos", R.string.AllVideos), photoEntry);
                                allVideosAlbum.videoOnly = true;
                                int index = 0;
                                if (allMediaAlbum != null) {
                                    index++;
                                }
                                if (allPhotosAlbum != null) {
                                    index++;
                                }
                                mediaAlbumsSorted.add(index, allVideosAlbum);
                            }
                            if (allMediaAlbum == null) {
                                allMediaAlbum = new AlbumEntry(0, LocaleController.getString("AllMedia", R.string.AllMedia), photoEntry);
                                mediaAlbumsSorted.add(0, allMediaAlbum);
                            }
                            allVideosAlbum.addPhoto(photoEntry);
                            allMediaAlbum.addPhoto(photoEntry);

                            AlbumEntry albumEntry = mediaAlbums.get(bucketId);
                            if (albumEntry == null) {
                                albumEntry = new AlbumEntry(bucketId, bucketName, photoEntry);
                                mediaAlbums.put(bucketId, albumEntry);
                                if (mediaCameraAlbumId == null && cameraFolder != null && path != null && path.startsWith(cameraFolder)) {
                                    mediaAlbumsSorted.add(0, albumEntry);
                                    mediaCameraAlbumId = bucketId;
                                } else {
                                    mediaAlbumsSorted.add(albumEntry);
                                }
                            }

                            albumEntry.addPhoto(photoEntry);
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
            for (int a = 0; a < mediaAlbumsSorted.size(); a++) {
                Collections.sort(mediaAlbumsSorted.get(a).photos, (o1, o2) -> {
                    if (o1.dateTaken < o2.dateTaken) {
                        return 1;
                    } else if (o1.dateTaken > o2.dateTaken) {
                        return -1;
                    }
                    return 0;
                });
            }
            broadcastNewPhotos(guid, mediaAlbumsSorted, photoAlbumsSorted, mediaCameraAlbumId, allMediaAlbum, allPhotosAlbum, allVideosAlbum, 0);
        });
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void broadcastNewPhotos(final int guid, final ArrayList<AlbumEntry> mediaAlbumsSorted, final ArrayList<AlbumEntry> photoAlbumsSorted, final Integer cameraAlbumIdFinal, final AlbumEntry allMediaAlbumFinal, final AlbumEntry allPhotosAlbumFinal, final AlbumEntry allVideosAlbumFinal, int delay) {
        if (broadcastPhotosRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(broadcastPhotosRunnable);
        }
        AndroidUtilities.runOnUIThread(broadcastPhotosRunnable = () -> {
            if (PhotoViewer.getInstance().isVisible()) {
                broadcastNewPhotos(guid, mediaAlbumsSorted, photoAlbumsSorted, cameraAlbumIdFinal, allMediaAlbumFinal, allPhotosAlbumFinal, allVideosAlbumFinal, 1000);
                return;
            }
            allMediaAlbums = mediaAlbumsSorted;
            allPhotoAlbums = photoAlbumsSorted;
            broadcastPhotosRunnable = null;
            allPhotosAlbumEntry = allPhotosAlbumFinal;
            allMediaAlbumEntry = allMediaAlbumFinal;
            allVideosAlbumEntry = allVideosAlbumFinal;
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.albumsDidLoad, guid, mediaAlbumsSorted, photoAlbumsSorted, cameraAlbumIdFinal);
        }, delay);
    }

    public void scheduleVideoConvert(MessageObject messageObject) {
        scheduleVideoConvert(messageObject, false);
    }

    public boolean scheduleVideoConvert(MessageObject messageObject, boolean isEmpty) {
        if (messageObject == null || messageObject.videoEditedInfo == null) {
            return false;
        }
        if (isEmpty && !videoConvertQueue.isEmpty()) {
            return false;
        } else if (isEmpty) {
            new File(messageObject.messageOwner.attachPath).delete();
        }
        videoConvertQueue.add(new VideoConvertMessage(messageObject, messageObject.videoEditedInfo));
        if (videoConvertQueue.size() == 1) {
            startVideoConvertFromQueue();
        }
        return true;
    }

    public void cancelVideoConvert(MessageObject messageObject) {
        if (messageObject != null) {
            if (!videoConvertQueue.isEmpty()) {
                for (int a = 0; a < videoConvertQueue.size(); a++) {
                    VideoConvertMessage videoConvertMessage = videoConvertQueue.get(a);
                    MessageObject object = videoConvertMessage.messageObject;
                    if (object.equals(messageObject) && object.currentAccount == messageObject.currentAccount) {
                        if (a == 0) {
                            synchronized (videoConvertSync) {
                                videoConvertMessage.videoEditedInfo.canceled = true;
                            }
                        } else {
                            videoConvertQueue.remove(a);
                        }
                        break;
                    }
                }
            }
        }
    }

    private boolean startVideoConvertFromQueue() {
        if (!videoConvertQueue.isEmpty()) {
            VideoConvertMessage videoConvertMessage = videoConvertQueue.get(0);
            MessageObject messageObject = videoConvertMessage.messageObject;
            VideoEditedInfo videoEditedInfo = videoConvertMessage.videoEditedInfo;
            synchronized (videoConvertSync) {
                if (videoEditedInfo != null) {
                    videoEditedInfo.canceled = false;
                }
            }
            Intent intent = new Intent(ApplicationLoader.applicationContext, VideoEncodingService.class);
            intent.putExtra("path", messageObject.messageOwner.attachPath);
            intent.putExtra("currentAccount", messageObject.currentAccount);
            if (messageObject.messageOwner.media.document != null) {
                for (int a = 0; a < messageObject.messageOwner.media.document.attributes.size(); a++) {
                    TLRPC.DocumentAttribute documentAttribute = messageObject.messageOwner.media.document.attributes.get(a);
                    if (documentAttribute instanceof TLRPC.TL_documentAttributeAnimated) {
                        intent.putExtra("gif", true);
                        break;
                    }
                }
            }
            if (messageObject.getId() != 0) {
                try {
                    ApplicationLoader.applicationContext.startService(intent);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            VideoConvertRunnable.runConversion(videoConvertMessage);
            return true;
        }
        return false;
    }

    @SuppressLint("NewApi")
    public static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        MediaCodecInfo lastCodecInfo = null;
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(mimeType)) {
                    lastCodecInfo = codecInfo;
                    String name = lastCodecInfo.getName();
                    if (name != null) {
                        if (!name.equals("OMX.SEC.avc.enc")) {
                            return lastCodecInfo;
                        } else if (name.equals("OMX.SEC.AVC.Encoder")) {
                            return lastCodecInfo;
                        }
                    }
                }
            }
        }
        return lastCodecInfo;
    }

    private static boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                return false;
        }
    }

    @SuppressLint("NewApi")
    public static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        int lastColorFormat = 0;
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            if (isRecognizedFormat(colorFormat)) {
                lastColorFormat = colorFormat;
                if (!(codecInfo.getName().equals("OMX.SEC.AVC.Encoder") && colorFormat == 19)) {
                    return colorFormat;
                }
            }
        }
        return lastColorFormat;
    }

    public static int findTrack(MediaExtractor extractor, boolean audio) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (audio) {
                if (mime.startsWith("audio/")) {
                    return i;
                }
            } else {
                if (mime.startsWith("video/")) {
                    return i;
                }
            }
        }
        return -5;
    }

    private void didWriteData(final VideoConvertMessage message, final File file, final boolean last, final long lastFrameTimestamp, long availableSize, final boolean error, final float progress) {
        final boolean firstWrite = message.videoEditedInfo.videoConvertFirstWrite;
        if (firstWrite) {
            message.videoEditedInfo.videoConvertFirstWrite = false;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (error || last) {
                synchronized (videoConvertSync) {
                    message.videoEditedInfo.canceled = false;
                }
                videoConvertQueue.remove(message);
                startVideoConvertFromQueue();
            }
            if (error) {
                NotificationCenter.getInstance(message.currentAccount).postNotificationName(NotificationCenter.filePreparingFailed, message.messageObject, file.toString(), progress, lastFrameTimestamp);
            } else {
                if (firstWrite) {
                    NotificationCenter.getInstance(message.currentAccount).postNotificationName(NotificationCenter.filePreparingStarted, message.messageObject, file.toString(), progress, lastFrameTimestamp);
                }
                NotificationCenter.getInstance(message.currentAccount).postNotificationName(NotificationCenter.fileNewChunkAvailable, message.messageObject, file.toString(), availableSize, last ? file.length() : 0, progress, lastFrameTimestamp);
            }
        });
    }

    public void pauseByRewind() {
        if (audioPlayer != null) {
            audioPlayer.pause();
        }
    }

    public void resumeByRewind() {
        if (audioPlayer != null && playingMessageObject != null && !isPaused) {
            if (audioPlayer.isBuffering()) {
                MessageObject currentMessageObject = playingMessageObject;
                cleanupPlayer(false, false);
                playMessage(currentMessageObject);
            } else {
                audioPlayer.play();
            }
        }
    }


    private static class VideoConvertRunnable implements Runnable {

        private VideoConvertMessage convertMessage;

        private VideoConvertRunnable(VideoConvertMessage message) {
            convertMessage = message;
        }

        @Override
        public void run() {
            MediaController.getInstance().convertVideo(convertMessage);
        }

        public static void runConversion(final VideoConvertMessage obj) {
            new Thread(() -> {
                try {
                    VideoConvertRunnable wrapper = new VideoConvertRunnable(obj);
                    Thread th = new Thread(wrapper, "VideoConvertRunnable");
                    th.start();
                    th.join();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }).start();
        }
    }


    private boolean convertVideo(final VideoConvertMessage convertMessage) {
        MessageObject messageObject = convertMessage.messageObject;
        VideoEditedInfo info = convertMessage.videoEditedInfo;
        if (messageObject == null || info == null) {
            return false;
        }
        String videoPath = info.originalPath;
        long startTime = info.startTime;
        long avatarStartTime = info.avatarStartTime;
        long endTime = info.endTime;
        int resultWidth = info.resultWidth;
        int resultHeight = info.resultHeight;
        int rotationValue = info.rotationValue;
        int originalWidth = info.originalWidth;
        int originalHeight = info.originalHeight;
        int framerate = info.framerate;
        int bitrate = info.bitrate;
        int originalBitrate = info.originalBitrate;
        boolean isSecret = DialogObject.isEncryptedDialog(messageObject.getDialogId());
        final File cacheFile = new File(messageObject.messageOwner.attachPath);
        if (cacheFile.exists()) {
            cacheFile.delete();
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("begin convert " + videoPath + " startTime = " + startTime + " avatarStartTime = " + avatarStartTime + " endTime " + endTime + " rWidth = " + resultWidth + " rHeight = " + resultHeight + " rotation = " + rotationValue + " oWidth = " + originalWidth + " oHeight = " + originalHeight + " framerate = " + framerate + " bitrate = " + bitrate + " originalBitrate = " + originalBitrate);
        }

        if (videoPath == null) {
            videoPath = "";
        }

        long duration;
        if (startTime > 0 && endTime > 0) {
            duration = endTime - startTime;
        } else if (endTime > 0) {
            duration = endTime;
        } else if (startTime > 0) {
            duration = info.originalDuration - startTime;
        } else {
            duration = info.originalDuration;
        }

        if (framerate == 0) {
            framerate = 25;
        }
//        else if (framerate > 59) {
//            framerate = 59;
//        }

        if (rotationValue == 90 || rotationValue == 270) {
            int temp = resultHeight;
            resultHeight = resultWidth;
            resultWidth = temp;
        }

//        if (!info.shouldLimitFps && framerate > 40 && (Math.min(resultHeight, resultWidth) <= 480)) {
//            framerate = 30;
//        }
//
        boolean needCompress = avatarStartTime != -1 || info.cropState != null || info.mediaEntities != null || info.paintPath != null || info.filterState != null ||
                resultWidth != originalWidth || resultHeight != originalHeight || rotationValue != 0 || info.roundVideo || startTime != -1;


        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("videoconvert", Activity.MODE_PRIVATE);

        long time = System.currentTimeMillis();

        VideoConvertorListener callback = new VideoConvertorListener() {

            private long lastAvailableSize = 0;

            @Override
            public boolean checkConversionCanceled() {
                return info.canceled;
            }

            @Override
            public void didWriteData(long availableSize, float progress) {
                if (info.canceled) {
                    return;
                }
                if (availableSize < 0) {
                    availableSize = cacheFile.length();
                }

                if (!info.needUpdateProgress && lastAvailableSize == availableSize) {
                    return;
                }

                lastAvailableSize = availableSize;
                MediaController.this.didWriteData(convertMessage, cacheFile, false, 0, availableSize, false, progress);
            }
        };

        info.videoConvertFirstWrite = true;

        MediaCodecVideoConvertor videoConvertor = new MediaCodecVideoConvertor();
        boolean error = videoConvertor.convertVideo(videoPath, cacheFile,
                rotationValue, isSecret,
                originalWidth, originalHeight,
                resultWidth, resultHeight,
                framerate, bitrate, originalBitrate,
                startTime, endTime, avatarStartTime,
                needCompress, duration,
                info.filterState,
                info.paintPath,
                info.mediaEntities,
                info.isPhoto,
                info.cropState,
                info.roundVideo,
                callback);


        boolean canceled = info.canceled;
        if (!canceled) {
            synchronized (videoConvertSync) {
                canceled = info.canceled;
            }
        }

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("time=" + (System.currentTimeMillis() - time) + " canceled=" + canceled);
        }

        preferences.edit().putBoolean("isPreviousOk", true).apply();
        didWriteData(convertMessage, cacheFile, true, videoConvertor.getLastFrameTimestamp(), cacheFile.length(), error || canceled, 1f);

        return true;
    }

    public static int getVideoBitrate(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        int bitrate = 0;
        try {
            retriever.setDataSource(path);
            bitrate = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
        } catch (Exception e) {
            FileLog.e(e);
        }

        try {
            retriever.release();
        } catch (Throwable throwable) {
            FileLog.e(throwable);
        }
        return bitrate;
    }

    public static int makeVideoBitrate(int originalHeight, int originalWidth, int originalBitrate, int height, int width) {
        float compressFactor;
        float minCompressFactor;
        int maxBitrate;
        if (Math.min(height, width) >= 1080) {
            maxBitrate = 6800_000;
            compressFactor = 1f;
            minCompressFactor = 1f;
        } else if (Math.min(height, width) >= 720) {
            maxBitrate = 3200_000;
            compressFactor = 1.0f;
            minCompressFactor = 1.0f;
        } else if (Math.min(height, width) >= 480) {
            maxBitrate = 1000_000;
            compressFactor = 0.75f;
            minCompressFactor = 0.9f;
        } else {
            maxBitrate = 750_000;
            compressFactor = 0.6f;
            minCompressFactor = 0.7f;
        }
        int remeasuredBitrate = (int) (originalBitrate / (Math.min(originalHeight / (float) (height), originalWidth / (float) (width))));
        remeasuredBitrate *= compressFactor;
        int minBitrate = (int) (getVideoBitrateWithFactor(minCompressFactor) / (1280f * 720f / (width * height)));
        if (originalBitrate < minBitrate) {
            return remeasuredBitrate;
        }
        if (remeasuredBitrate > maxBitrate) {
            return maxBitrate;
        }
        return Math.max(remeasuredBitrate, minBitrate);
    }

    private static int getVideoBitrateWithFactor(float f) {
        return (int) (f * 2000f * 1000f * 1.13f);
    }

    public interface VideoConvertorListener {
        boolean checkConversionCanceled();

        void didWriteData(long availableSize, float progress);
    }

    public static class PlaylistGlobalSearchParams {
        final String query;
        final FiltersView.MediaFilterData filter;
        final long dialogId;
        final long minDate;
        final long maxDate;
        public int totalCount;
        public boolean endReached;
        public int nextSearchRate;
        public int folderId;

        public PlaylistGlobalSearchParams(String query, long dialogId, long minDate, long maxDate, FiltersView.MediaFilterData filter) {
            this.filter = filter;
            this.query = query;
            this.dialogId = dialogId;
            this.minDate = minDate;
            this.maxDate = maxDate;
        }
    }

    public boolean currentPlaylistIsGlobalSearch() {
        return playlistGlobalSearchParams != null;
    }
}
