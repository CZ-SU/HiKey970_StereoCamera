package com.vinda.ucvcamera;

import android.Manifest;
import android.speech.tts.TextToSpeech;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.serenegiant.common.BaseActivity;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usbcameracommon.UVCCameraHandler;
import com.serenegiant.usbcameracommon.UvcCameraDataCallBack;
import com.serenegiant.widget.CameraViewInterface;
import com.serenegiant.widget.UVCCameraTextureView;
import com.yuan.camera.R;

import com.vinda.ucvcamera.customview.OverlayView;
import com.vinda.ucvcamera.customview.OverlayView.DrawCallback;
import com.vinda.ucvcamera.env.BorderedText;
import com.vinda.ucvcamera.env.ImageUtils;
import com.vinda.ucvcamera.env.Logger;
import com.vinda.ucvcamera.tflite.Classifier;
import com.vinda.ucvcamera.tflite.TFLiteObjectDetectionAPIModel;
import com.vinda.ucvcamera.tracking.MultiBoxTracker;
import com.vinda.ucvcamera.Nv21toBitmap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static java.lang.Math.abs;


/**
 * 显示多路摄像头
 */
public class MainActivity extends BaseActivity  implements CameraDialog.CameraDialogParent, View.OnClickListener, TextToSpeech.OnInitListener{
    private static final boolean DEBUG = false;
    private static final String TAG = "MainActivity";

    private static final float[] BANDWIDTH_FACTORS = {0.5f, 0.5f};

    public int ple=0;
    public int pri=0;

    public static int lefir=0;
    public static int rifir=0;

    //读写权限
    private static String[] PERMISSIONS_STORAGE = {
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE};    //请求状态码
    private static int REQUEST_PERMISSION_CODE = 2;

    // for accessing USB and USB camera
    private USBMonitor mUSBMonitor;

    private UVCCameraHandler mHandlerFirst;
    private CameraViewInterface mUVCCameraViewFirst;
    private ImageButton mCaptureButtonFirst;
    private Surface mFirstPreviewSurface;

    private UVCCameraHandler mHandlerSecond;
    private CameraViewInterface mUVCCameraViewSecond;
    private ImageButton mCaptureButtonSecond;
    private Surface mSecondPreviewSurface;

    // Configuration values for the prepackaged SSD model.
    private static final int TF_OD_API_INPUT_SIZE = 300;
    private static final boolean TF_OD_API_IS_QUANTIZED = true;
    private static final String TF_OD_API_MODEL_FILE = "detect.tflite";
    private static final String TF_OD_API_LABELS_FILE = "file:///android_asset/labelmap.txt";

    private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.60f;
    private MultiBoxTracker tracker;
    private Classifier leftdetector;
    private Classifier rightdetector;

    private Bitmap leftdetecBitmap = null;
    private Bitmap leftfirstBitmap = null;
    private Bitmap rightdetecBitmap = null;
    private Bitmap rightfirstBitmap = null;

    public static Context context;

    public TextView tv2;
    public TextView tv3;
    public TextView tv4;

    private Button speechBtn; // 按钮控制开始朗读
    private TextToSpeech textToSpeech; // TTS对象

    public float z_real = 0.0f;
    public float side_real = 0.0f;

    public float get_leftx = 0.0f;
    public float get_lefty = 0.0f;
    public float get_rightx = 0.0f;
    public float get_righty = 0.0f;

    public int left_sign = 0;
    public int right_sign = 0;

    public String left_object = null;
    public String right_object = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this, PERMISSIONS_STORAGE, REQUEST_PERMISSION_CODE);
            }
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_surface_view_camera);

        findViewById(R.id.RelativeLayout1).setOnClickListener(mOnClickListener);
        tv2 = (TextView)findViewById(R.id.textView2);
        tv3 = (TextView)findViewById(R.id.textView3);
        tv4 = (TextView)findViewById(R.id.textView4);
        speechBtn = (Button) findViewById(R.id.btn_read);
        speechBtn.setOnClickListener(this);
        textToSpeech = new TextToSpeech(this, this); // 参数Context,TextToSpeech.OnInitListener
        mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);
        resultFirstCamera();
        resultSecondCamera();

    }

    @Override
    public void onInit(int status) {
        //程序初始化设定，检测TTS模块是否正常。。
        if (status == TextToSpeech.SUCCESS) {
            int result = textToSpeech.setLanguage(Locale.ENGLISH);
            if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "数据丢失或不支持", Toast.LENGTH_SHORT).show();
            }
        }
    }

    //@Override
    public void sdoutput(String a) {
        if (textToSpeech != null && !textToSpeech.isSpeaking()) {
            // 设置音调，值越大声音越尖（女生），值越小则变成男声,1.0是常规
            textToSpeech.setPitch(2.0f);
            //设定语速 ，默认1.0正常语速
            textToSpeech.setSpeechRate(1.0f);
            // set the voice
            textToSpeech.setPitch(1.0f);
            //朗读，注意这里三个参数的added in API level 4   四个参数的added in API level 21
            //------要直接朗读字符串，可将“speechTxt.getText().toString()”替换为所需字符串***********

            //textToSpeech.speak(speechTxt.getText().toString(), TextToSpeech.QUEUE_FLUSH, null);
            //textToSpeech.speak("someting in the left" + a + "degree", TextToSpeech.QUEUE_FLUSH, null);
            textToSpeech.speak(a+" ", TextToSpeech.QUEUE_FLUSH, null);

            //Log.d("text", textToSpeech.Voice());
        }
    }


    @Override
    public void onClick(View v) {
        if (textToSpeech != null && !textToSpeech.isSpeaking()) {
            // 设置音调，值越大声音越尖（女生），值越小则变成男声,1.0是常规
            textToSpeech.setPitch(2.0f);
            //设定语速 ，默认1.0正常语速
            textToSpeech.setSpeechRate(1.0f);
            // set the voice
            textToSpeech.setPitch(1.0f);
            //朗读，注意这里三个参数的added in API level 4   四个参数的added in API level 21
            //------要直接朗读字符串，可将“speechTxt.getText().toString()”替换为所需字符串***********
            textToSpeech.speak(tv4.getText().toString(), TextToSpeech.QUEUE_FLUSH, null);
            tv4.setText("                                                     ");
            //Log.d("text", textToSpeech.Voice());
        }
    }

    /**
     * 带有回调数据的初始化
     */
    private void resultFirstCamera() {
        mUVCCameraViewFirst = (CameraViewInterface) findViewById(R.id.camera_view_first);
        //设置显示宽高
        mUVCCameraViewFirst.setAspectRatio(UVCCamera.DEFAULT_PREVIEW_WIDTH / (float) UVCCamera.DEFAULT_PREVIEW_HEIGHT);

        ((UVCCameraTextureView) mUVCCameraViewFirst).setOnClickListener(mOnClickListener);

        mCaptureButtonFirst = (ImageButton) findViewById(R.id.capture_button_first);
        mCaptureButtonFirst.setOnClickListener(mOnClickListener);
        mCaptureButtonFirst.setVisibility(View.INVISIBLE);

        mHandlerFirst = UVCCameraHandler.createHandler(this, mUVCCameraViewFirst
                , UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT
                , BANDWIDTH_FACTORS[0], firstDataCallBack);
    }

    UvcCameraDataCallBack firstDataCallBack = new UvcCameraDataCallBack() {
        @Override
        public void getData(byte[] data) {

            if(ple==1 && pri==1)
            {
                left_sign = 0;
                if(lefir==0)
                {
                    lefir=1;
                    leftClassifierInit(new Size(640, 480), 0);
                }
                else
                {
                    context = getApplicationContext();
                    leftfirstBitmap = new Nv21toBitmap(context).nv21ToBitmap(data, 640, 480);

                    leftdetecBitmap = Bitmap2Change(leftfirstBitmap, 300, 300);
                    leftprocessImage();
                }
            }
        }
    };


    private void resultSecondCamera() {
        mUVCCameraViewSecond = (CameraViewInterface) findViewById(R.id.camera_view_second);
        mUVCCameraViewSecond.setAspectRatio(UVCCamera.DEFAULT_PREVIEW_WIDTH / (float) UVCCamera.DEFAULT_PREVIEW_HEIGHT);
        ((UVCCameraTextureView) mUVCCameraViewSecond).setOnClickListener(mOnClickListener);
        mCaptureButtonSecond = (ImageButton) findViewById(R.id.capture_button_second);
        mCaptureButtonSecond.setOnClickListener(mOnClickListener);
        mCaptureButtonSecond.setVisibility(View.INVISIBLE);
        mHandlerSecond = UVCCameraHandler.createHandler(this, mUVCCameraViewSecond
                , UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT
                , BANDWIDTH_FACTORS[1], secondDataCallBack);
    }

    UvcCameraDataCallBack secondDataCallBack = new UvcCameraDataCallBack() {
        @Override
        public void getData(byte[] data) {

            if(ple==1 && pri==1)
            {
                right_sign = 0;
                if(rifir==0)
                {
                    rifir=1;
                    rightClassifierInit(new Size(640, 480), 0);
                }
                else
                {
                    context = getApplicationContext();
                    rightfirstBitmap = new Nv21toBitmap(context).nv21ToBitmap(data, 640, 480);

                    rightdetecBitmap = Bitmap2Change(rightfirstBitmap, 300, 300);
                    rightprocessImage();
                }
            }
        }
    };

    public void Po_z(float left_x, float left_y, float right_x, float right_y){

        float left_x0 = 344.11500f;
        float left_y0 = 249.95075f;
        float right_x0 = 323.83109f;
        float right_y0 = 241.28088f;

        float x_sacle = 2.1333333333333f;
        float y_scale = 1.6000000000000f;

        float left_u = left_x*x_sacle - left_x0;
        float left_v = left_y*y_scale - left_y0;
        float right_u = right_x*x_sacle - right_x0;
        float right_v = right_y*y_scale - right_y0;

        float B = 0.018f;
        float f = 0.008f;
        float s_area = 5.912786e-6f;

        float D = abs(left_u - right_u);
        float D_real = D*s_area;

        z_real = B*f/D_real;

        if(left_x < 150){
            side_real = 1;
        }
        else if(left_x > 150){
            side_real = 2;
        }
        else {
            side_real = 3;
        }
    }

    protected void leftprocessImage() {
        //   !!!!
        final List<Classifier.Recognition> results = leftdetector.recognizeImage(leftdetecBitmap);

        float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
        tv2.setText("                                                                            ");

        for (final Classifier.Recognition result : results) {
            final RectF location = result.getLocation();
            if (location != null && result.getConfidence() >= minimumConfidence) {

                result.setLocation(location);

                if(result.getTitle().equals("chair")){
                    tv2.setText(" LEFT_Get Object:  "+result.getTitle()+"   Confidence:"+result.getConfidence());
                    get_leftx = result.toCenterX();
                    get_lefty = result.toCenterY();
                    left_object = result.getTitle();

                    if(right_sign==1){

                        Po_z(get_leftx, get_lefty, get_rightx, get_righty);

                        BigDecimal mid = new BigDecimal(z_real);
                        z_real = mid.setScale(2,BigDecimal.ROUND_HALF_UP).floatValue();

                        if(z_real>0.2 && z_real<6.8){
                            String outtext = " ";
                            if(side_real==1){
                                // on the left
                                tv4.setText("左前方大概"+z_real+"米"+"有椅子");
                                outtext = "左前方大概"+z_real+"米"+"有椅子";
                                sdoutput(outtext);
                            } else if(side_real==2){
                                // on the right
                                tv4.setText("右前方大概"+z_real+"米"+"有椅子");
                                outtext = "右前方大概"+z_real+"米"+"有椅子";
                                sdoutput(outtext);
                            } else if(side_real==3){
                                // on the middle
                                tv4.setText("前方大概"+z_real+"米"+"有椅子");
                                outtext = "前方大概"+z_real+"米"+"有椅子";
                                sdoutput(outtext);
                            }
                        }

                    } else{
                        //tv4.setText("                                         ");
                    }

                    left_sign = 1;
                    break;
                }
            }
        }
    }

    public void leftClassifierInit(final Size size, final int rotation) {

        try {

            leftdetector =
                    TFLiteObjectDetectionAPIModel.create(
                            getAssets(),
                            TF_OD_API_MODEL_FILE,
                            TF_OD_API_LABELS_FILE,
                            TF_OD_API_INPUT_SIZE,
                            TF_OD_API_IS_QUANTIZED);

        } catch (final IOException e) {
            Log.i("ST", "Classifier could not be initialized");
        }
    }

    protected void rightprocessImage() {
        //   !!!!
        final List<Classifier.Recognition> results = rightdetector.recognizeImage(rightdetecBitmap);

        float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;

        tv3.setText("                                                                            ");
        for (final Classifier.Recognition result : results) {
            final RectF location = result.getLocation();
            if (location != null && result.getConfidence() >= minimumConfidence) {

                result.setLocation(location);

                if(result.getTitle().equals("chair")){
                    tv3.setText("RIGHT_Get Object:  "+result.getTitle()+"   Confidence:"+result.getConfidence());
                    get_rightx = result.toCenterX();
                    get_righty = result.toCenterY();
                    right_object = result.getTitle();

                    if(left_sign==1){

                        Po_z(get_leftx, get_lefty, get_rightx, get_righty);

                        BigDecimal mid = new BigDecimal(z_real);
                        z_real = mid.setScale(2,BigDecimal.ROUND_HALF_UP).floatValue();

                        if(z_real>0.2 && z_real<6.8){
                            String outtext = " ";
                            if(side_real==1){
                                // on the left
                                tv4.setText("左前方大概"+z_real+"米"+"有椅子");
                                outtext = "左前方大概"+z_real+"米"+"有椅子";
                                sdoutput(outtext);
                            } else if(side_real==2){
                                // on the right
                                tv4.setText("右前方大概"+z_real+"米"+"有椅子");
                                outtext = "右前方大概"+z_real+"米"+"有椅子";
                                sdoutput(outtext);
                            } else if(side_real==3){
                                // on the middle
                                tv4.setText("前方大概"+z_real+"米"+"有椅子");
                                outtext = "前方大概"+z_real+"米"+"有椅子";
                                sdoutput(outtext);
                            }
                        }

                    } else{
                        //tv4.setText("                                         ");
                    }

                    right_sign = 1;
                    break;
                }
            }
        }
    }

    public void rightClassifierInit(final Size size, final int rotation) {

        try {

            rightdetector =
                    TFLiteObjectDetectionAPIModel.create(
                            getAssets(),
                            TF_OD_API_MODEL_FILE,
                            TF_OD_API_LABELS_FILE,
                            TF_OD_API_INPUT_SIZE,
                            TF_OD_API_IS_QUANTIZED);

        } catch (final IOException e) {
            Log.i("ST", "Classifier could not be initialized");
        }
    }

    // Bitmap --> availe
    public Bitmap Bitmap2Change(Bitmap bm, int width, int height)
    {
        int w = bm.getWidth();
        int h = bm.getHeight();
        Matrix mat = new Matrix();
        float scalewidth = ( (float)width/w );
        float scaleheight = ( (float)height/h );
        mat.postScale( scalewidth, scaleheight );
        Bitmap bitmap = Bitmap.createBitmap(bm, 0, 0, w, h, mat, true);

        return bitmap;
    }

    // slow
    private static Bitmap nv21ToBitmap(byte[] nv21, int width, int height) {
        Bitmap bitmap = null;
        try {
            YuvImage image = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            image.compressToJpeg(new Rect(0, 0, width, height), 80, stream);
            bitmap = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size());
            stream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return bitmap;
    }

    public void saveImage(Bitmap btImage)
    {
        File file = new File(Environment.getExternalStorageDirectory(), "STT2.png");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            btImage.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        try {
            out.flush();
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mUSBMonitor.register();
        if (mUVCCameraViewSecond != null)
            mUVCCameraViewSecond.onResume();
        if (mUVCCameraViewFirst != null)
            mUVCCameraViewFirst.onResume();
    }

    @Override
    protected void onStop() {
        mHandlerFirst.close();
        if (mUVCCameraViewFirst != null)
            mUVCCameraViewFirst.onPause();
        mCaptureButtonFirst.setVisibility(View.INVISIBLE);

        mHandlerSecond.close();
        if (mUVCCameraViewSecond != null)
            mUVCCameraViewSecond.onPause();
        mCaptureButtonSecond.setVisibility(View.INVISIBLE);

        mUSBMonitor.unregister();//usb管理器解绑
        super.onStop();

        textToSpeech.stop(); // 不管是否正在朗读TTS都被打断
        textToSpeech.shutdown(); // 关闭，释放资源
    }

    @Override
    protected void onDestroy() {
        if (mHandlerFirst != null) {
            mHandlerFirst = null;
        }

        if (mHandlerSecond != null) {
            mHandlerSecond = null;
        }

        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }

        mUVCCameraViewFirst = null;
        mCaptureButtonFirst = null;

        mUVCCameraViewSecond = null;
        mCaptureButtonSecond = null;

        super.onDestroy();
    }

    private final View.OnClickListener mOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(final View view) {
            switch (view.getId()) {
                case R.id.camera_view_first:
                    if (mHandlerFirst != null) {
                        if (!mHandlerFirst.isOpened()) {
                            CameraDialog.showDialog(MainActivity.this);
                        } else {
                            mHandlerFirst.close();
                            setCameraButton();
                        }
                    }
                    break;
                case R.id.capture_button_first:
                    if (mHandlerFirst != null) {
                        if (mHandlerFirst.isOpened()) {
                            if (checkPermissionWriteExternalStorage() && checkPermissionAudio()) {
                                if (!mHandlerFirst.isRecording()) {
                                    mCaptureButtonFirst.setColorFilter(0xffff0000);    // turn red
                                    mHandlerFirst.startRecording();
                                } else {
                                    mCaptureButtonFirst.setColorFilter(0);    // return to default color
                                    mHandlerFirst.stopRecording();
                                }
                            }
                        }
                    }
                    break;
                case R.id.camera_view_second:
                    if (mHandlerSecond != null) {
                        if (!mHandlerSecond.isOpened()) {
                            CameraDialog.showDialog(MainActivity.this);
                        } else {
                            mHandlerSecond.close();
                            setCameraButton();
                        }
                    }
                    break;
                case R.id.capture_button_second:
                    if (mHandlerSecond != null) {
                        if (mHandlerSecond.isOpened()) {
                            if (checkPermissionWriteExternalStorage() && checkPermissionAudio()) {
                                if (!mHandlerSecond.isRecording()) {
                                    mCaptureButtonSecond.setColorFilter(0xffff0000);    // turn red
                                    mHandlerSecond.startRecording();
                                } else {
                                    mCaptureButtonSecond.setColorFilter(0);    // return to default color
                                    mHandlerSecond.stopRecording();
                                }
                            }
                        }
                    }
                    break;
            }
        }
    };

    private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onAttach:" + device);
            Toast.makeText(MainActivity.this, "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onConnect(final UsbDevice device, final UsbControlBlock ctrlBlock, final boolean createNew) {
            //设备连接成功
            if (DEBUG) Log.v(TAG, "onConnect:" + device);
            if (!mHandlerFirst.isOpened()) {
                mHandlerFirst.open(ctrlBlock);
                final SurfaceTexture st = mUVCCameraViewFirst.getSurfaceTexture();
                mHandlerFirst.startPreview(new Surface(st));

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mCaptureButtonFirst.setVisibility(View.VISIBLE);
                    }
                });
                ple = 1;

            } else if (!mHandlerSecond.isOpened()) {
                mHandlerSecond.open(ctrlBlock);
                final SurfaceTexture st = mUVCCameraViewSecond.getSurfaceTexture();
                mHandlerSecond.startPreview(new Surface(st));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mCaptureButtonSecond.setVisibility(View.VISIBLE);
                    }
                });
                pri = 1;

            }
        }

        @Override
        public void onDisconnect(final UsbDevice device, final UsbControlBlock ctrlBlock) {
            if (DEBUG) Log.v(TAG, "onDisconnect:" + device);
            if ((mHandlerFirst != null) && !mHandlerFirst.isEqual(device)) {
                queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        mHandlerFirst.close();
                        if (mFirstPreviewSurface != null) {
                            mFirstPreviewSurface.release();
                            mFirstPreviewSurface = null;
                        }
                        setCameraButton();
                    }
                }, 0);
            } else if ((mHandlerSecond != null) && !mHandlerSecond.isEqual(device)) {
                queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        mHandlerSecond.close();
                        if (mSecondPreviewSurface != null) {
                            mSecondPreviewSurface.release();
                            mSecondPreviewSurface = null;
                        }
                        setCameraButton();
                    }
                }, 0);
            }
        }

        @Override
        public void onDettach(final UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onDettach:" + device);
            Toast.makeText(MainActivity.this, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel(final UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCancel:");
        }
    };

    /**
     * to access from CameraDialog
     *
     * @return
     */
    @Override
    public USBMonitor getUSBMonitor() {
        return mUSBMonitor;
    }

    // Which detection model to use: by default uses Tensorflow Object Detection API frozen
    // checkpoints.
    private enum DetectorMode {
        TF_OD_API;
    }

    @Override
    public void onDialogResult(boolean canceled) {
        if (canceled) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setCameraButton();
                }
            }, 0);
        }
    }

    private void setCameraButton() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if ((mHandlerFirst != null) && !mHandlerFirst.isOpened() && (mCaptureButtonFirst != null)) {
                    mCaptureButtonFirst.setVisibility(View.INVISIBLE);
                }
                if ((mHandlerSecond != null) && !mHandlerSecond.isOpened() && (mCaptureButtonSecond != null)) {
                    mCaptureButtonSecond.setVisibility(View.INVISIBLE);
                }
            }
        }, 0);
    }


    // YUV21 --> rgb
    private static final int[] Table_fv1 = { -180, -179, -177, -176, -174, -173, -172, -170, -169, -167, -166, -165, -163, -162, -160, -159, -158, -156, -155, -153, -152, -151, -149, -148, -146, -145, -144, -142, -141, -139, -138, -137,  -135, -134, -132, -131, -130, -128, -127, -125, -124, -123, -121, -120, -118, -117, -115, -114, -113, -111, -110, -108, -107, -106, -104, -103, -101, -100, -99, -97, -96, -94, -93, -92, -90,  -89, -87, -86, -85, -83, -82, -80, -79, -78, -76, -75, -73, -72, -71, -69, -68, -66, -65, -64,-62, -61, -59, -58, -57, -55, -54, -52, -51, -50, -48, -47, -45, -44, -43, -41, -40, -38, -37,  -36, -34, -33, -31, -30, -29, -27, -26, -24, -23, -22, -20, -19, -17, -16, -15, -13, -12, -10, -9, -8, -6, -5, -3, -2, 0, 1, 2, 4, 5, 7, 8, 9, 11, 12, 14, 15, 16, 18, 19, 21, 22, 23, 25, 26, 28, 29, 30, 32, 33, 35, 36, 37, 39, 40, 42, 43, 44, 46, 47, 49, 50, 51, 53, 54, 56, 57, 58, 60, 61, 63, 64, 65, 67, 68, 70, 71, 72, 74, 75, 77, 78, 79, 81, 82, 84, 85, 86, 88, 89, 91, 92, 93, 95, 96, 98, 99, 100, 102, 103, 105, 106, 107, 109, 110, 112, 113, 114, 116, 117, 119, 120, 122, 123, 124, 126, 127, 129, 130, 131, 133, 134, 136, 137, 138, 140, 141, 143, 144, 145, 147, 148,  150, 151, 152, 154, 155, 157, 158, 159, 161, 162, 164, 165, 166, 168, 169, 171, 172, 173, 175, 176, 178 };
    private static final int[] Table_fv2 = { -92, -91, -91, -90, -89, -88, -88, -87, -86, -86, -85, -84, -83, -83, -82, -81, -81, -80, -79, -78, -78, -77, -76, -76, -75, -74, -73, -73, -72, -71, -71, -70, -69, -68, -68, -67, -66, -66, -65, -64, -63, -63, -62, -61, -61, -60, -59, -58, -58, -57, -56, -56, -55, -54, -53, -53, -52, -51, -51, -50, -49, -48, -48, -47, -46, -46, -45, -44, -43, -43, -42, -41, -41, -40, -39, -38, -38, -37, -36, -36, -35, -34, -33, -33, -32, -31, -31, -30, -29, -28, -28, -27, -26, -26, -25, -24, -23, -23, -22, -21, -21, -20, -19, -18, -18, -17, -16, -16, -15, -14, -13, -13, -12, -11, -11, -10, -9, -8, -8, -7, -6, -6, -5, -4, -3, -3, -2, -1, 0, 0, 1, 2, 2, 3, 4, 5, 5, 6, 7, 7, 8, 9, 10, 10, 11, 12, 12, 13, 14, 15, 15, 16, 17, 17, 18, 19, 20, 20, 21, 22, 22, 23, 24, 25, 25, 26, 27, 27, 28, 29, 30, 30, 31, 32, 32, 33, 34, 35, 35, 36, 37, 37, 38, 39, 40, 40, 41, 42, 42, 43, 44, 45, 45, 46, 47, 47, 48, 49, 50, 50, 51, 52, 52, 53, 54, 55, 55, 56, 57, 57, 58, 59, 60, 60, 61, 62, 62, 63, 64, 65, 65, 66, 67, 67, 68, 69, 70, 70, 71, 72, 72, 73, 74, 75, 75, 76, 77, 77, 78, 79, 80, 80, 81, 82, 82, 83, 84, 85, 85, 86, 87, 87, 88, 89, 90, 90 };
    private static final int[] Table_fu1 = { -44, -44, -44, -43, -43, -43, -42, -42, -42, -41, -41, -41, -40, -40, -40, -39, -39, -39, -38, -38, -38, -37, -37, -37, -36, -36, -36, -35, -35, -35, -34, -34, -33, -33, -33, -32, -32, -32, -31, -31, -31, -30, -30, -30, -29, -29, -29, -28, -28, -28, -27, -27, -27, -26, -26, -26, -25, -25, -25, -24, -24, -24, -23, -23, -22, -22, -22, -21, -21, -21, -20, -20, -20, -19, -19, -19, -18, -18, -18, -17, -17, -17, -16, -16, -16, -15, -15, -15, -14, -14, -14, -13, -13, -13, -12, -12, -11, -11, -11, -10, -10, -10, -9, -9, -9, -8, -8, -8, -7, -7, -7, -6, -6, -6, -5, -5, -5, -4, -4, -4, -3, -3, -3, -2, -2, -2, -1, -1, 0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 5, 6, 6, 6, 7, 7, 7, 8, 8, 8, 9, 9, 9, 10, 10, 11, 11, 11, 12, 12, 12, 13, 13, 13, 14, 14, 14, 15, 15, 15, 16, 16, 16, 17, 17, 17, 18, 18, 18, 19, 19, 19, 20, 20, 20, 21, 21, 22, 22, 22, 23, 23, 23, 24, 24, 24, 25, 25, 25, 26, 26, 26, 27, 27, 27, 28, 28, 28, 29, 29, 29, 30, 30, 30, 31, 31, 31, 32, 32, 33, 33, 33, 34, 34, 34, 35, 35, 35, 36, 36, 36, 37, 37, 37, 38, 38, 38, 39, 39, 39, 40, 40, 40, 41, 41, 41, 42, 42, 42, 43, 43 };
    private static final int[] Table_fu2 = { -227, -226, -224, -222, -220, -219, -217, -215, -213, -212, -210, -208, -206, -204, -203, -201, -199, -197, -196, -194, -192, -190, -188, -187, -185, -183, -181, -180, -178, -176, -174, -173, -171, -169, -167, -165, -164, -162, -160, -158, -157, -155, -153, -151, -149, -148, -146, -144, -142, -141, -139, -137, -135, -134, -132, -130, -128, -126, -125, -123, -121, -119, -118, -116, -114, -112, -110, -109, -107, -105, -103, -102, -100, -98, -96, -94, -93, -91, -89, -87, -86, -84, -82, -80, -79, -77, -75, -73, -71, -70, -68, -66, -64, -63, -61, -59, -57, -55, -54, -52, -50, -48, -47, -45, -43, -41, -40, -38, -36, -34, -32, -31, -29, -27, -25, -24, -22, -20, -18, -16, -15, -13, -11, -9, -8, -6, -4, -2, 0, 1, 3, 5, 7, 8, 10, 12, 14, 15, 17, 19, 21, 23, 24, 26, 28, 30, 31, 33, 35, 37, 39, 40, 42, 44, 46, 47, 49, 51, 53, 54, 56, 58, 60, 62, 63, 65, 67, 69, 70, 72, 74, 76, 78, 79, 81, 83, 85, 86, 88, 90, 92, 93, 95, 97, 99, 101, 102, 104, 106, 108, 109, 111, 113, 115, 117, 118, 120, 122, 124, 125, 127, 129, 131, 133, 134, 136, 138, 140, 141, 143, 145, 147, 148, 150, 152, 154, 156, 157, 159, 161, 163, 164, 166, 168, 170, 172, 173, 175, 177, 179, 180, 182, 184, 186, 187, 189, 191, 193, 195, 196, 198, 200, 202, 203, 205, 207, 209, 211, 212, 214, 216, 218, 219, 221, 223, 225 };

    public static byte[] YV21ToRGB_Table(byte[] pYV12, int width, int height)
    {
        final int nYLen =  width * height ;
        final int nHfWidth = (width>>1);
        byte[] pRGB24 = new byte[nYLen*3];

//        // Y data
//        //unsigned char* yData = pYV12;
//        byte[] yData = new byte[pYV12.length];
//        for(int i=0;i<pYV12.length;i++)
//            yData[i] = pYV12[i];
//        // v data
//        //unsigned char* vData = &yData[nYLen];
//        byte[] vData = new byte[yData.length - nYLen];
//        for(int i=0;i<vData.length;i++)
//            vData[i] = yData[nYLen + i];
//        // u data
//        //unsigned char* uData = &vData[nYLen>>2];
//        byte[] uData = new byte[vData.length - (nYLen>>2)];
//        for(int i=0;i<uData.length;i++)
//        {
//            uData[i] = vData[(nYLen>>2) + i];
//        }
        // Y data
        //unsigned char* yData = pYV12;
        byte[] yData = new byte[pYV12.length];
        for(int i=0;i<pYV12.length;i++)
            yData[i] = pYV12[i];
        // v data
        //unsigned char* vData = &yData[nYLen];
        byte[] uData = new byte[yData.length - nYLen];
        for(int i=0;i<uData.length;i++)
            uData[i] = yData[nYLen + i];
        // u data
        //unsigned char* uData = &vData[nYLen>>2];
        byte[] vData = new byte[uData.length - (nYLen>>2)];
        for(int i=0;i<vData.length;i++)
        {
            vData[i] = uData[(nYLen>>2) + i];
        }

//        if( uData.length == 0  ||  vData.length == 0)
//            return false;
        //int rgb[3];
        int[] rgb = new int[3];
        int i, j, m, n, x, y, pu, pv, py, rdif, invgdif, bdif;
        int k, idx;
        m = -width;
        n = -nHfWidth;
        //--------------------------------------
        boolean addhalf = true;
        for(y=0; y<height;y++)
        {
            m += width;
            if( addhalf ){
                n+=nHfWidth;
                addhalf = false;
            }
            else
            {
                addhalf = true;
            }
            for(x=0; x<width;x++)
            {
                i = m + x;
                j = n + (x>>1);

                py = (int)((yData[i] + 256)%256);

                // search tables to get rdif invgdif and bidif
                rdif = Table_fv1[(int)((vData[j] + 256)%256)];    // fv1
                invgdif = Table_fu1[(int)((uData[j]+ 256)%256)] + Table_fv2[(int)((vData[j] + 256)%256)]; // fu1+fv2
                bdif = Table_fu2[(int)((uData[j] + 256)%256)]; // fu2

                rgb[2] = py+rdif;    // R
                rgb[1] = py-invgdif; // G
                rgb[0] = py+bdif;    // B

                // copy this pixel to rgb data
                idx = (y * width + x) * 3;
                for(k=0; k<3; k++)
                    pRGB24[idx + k] = (byte)(rgb[k]<0? 0: rgb[k]>255? 255 : rgb[k]);
            }
        }
        return pRGB24;
    }

    // rgb --> Bitmap
    /**
     * @方法描述 将RGB字节数组转换成Bitmap，
     */
    static public Bitmap rgb2Bitmap(byte[] data, int width, int height) {
        int[] colors = convertByteToColor(data);    //取RGB值转换为int数组
        if (colors == null) {
            return null;
        }

        Bitmap bmp = Bitmap.createBitmap(colors, 0, width, width, height,
                Bitmap.Config.ARGB_8888);
        return bmp;
    }

    // 将一个byte数转成int
    // 实现这个函数的目的是为了将byte数当成无符号的变量去转化成int
    public static int convertByteToInt(byte data) {

        int heightBit = (int) ((data >> 4) & 0x0F);
        int lowBit = (int) (0x0F & data);
        return heightBit * 16 + lowBit;
    }

    // 将纯RGB数据数组转化成int像素数组
    public static int[] convertByteToColor(byte[] data) {
        int size = data.length;
        if (size == 0) {
            return null;
        }

        int arg = 0;
        if (size % 3 != 0) {
            arg = 1;
        }

        // 一般RGB字节数组的长度应该是3的倍数，
        // 不排除有特殊情况，多余的RGB数据用黑色0XFF000000填充
        int[] color = new int[size / 3 + arg];
        int red, green, blue;
        int colorLen = color.length;
        if (arg == 0) {
            for (int i = 0; i < colorLen; ++i) {
                red = convertByteToInt(data[i * 3]);
                green = convertByteToInt(data[i * 3 + 1]);
                blue = convertByteToInt(data[i * 3 + 2]);

                // 获取RGB分量值通过按位或生成int的像素值
                color[i] = (red << 16) | (green << 8) | blue | 0xFF000000;
            }
        } else {
            for (int i = 0; i < colorLen - 1; ++i) {
                red = convertByteToInt(data[i * 3]);
                green = convertByteToInt(data[i * 3 + 1]);
                blue = convertByteToInt(data[i * 3 + 2]);
                color[i] = (red << 16) | (green << 8) | blue | 0xFF000000;
            }
            color[colorLen - 1] = 0xFF000000;
        }
        return color;
    }
}
