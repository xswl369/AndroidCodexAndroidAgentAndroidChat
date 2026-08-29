package com.offlinevoice.input;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Vosk 离线语音识别引擎封装。
 *
 * <p>职责：把 assets 中的语音模型解压到应用私有目录（只解压一次）、
 * 持有全局唯一的 Model 单例、创建每次录音会话的 SpeechService。
 * 全程本地运行，不需要网络，不需要任何账号。</p>
 */
public final class VoskEngine {

    /** 模型音频采样率，Vosk 中文模型固定 16kHz。 */
    private static final float SAMPLE_RATE = 16000.0f;

    /** 已加载的模型（全局单例，进程内只加载一次）。 */
    private static volatile Model model;

    /** 是否正在解压/加载模型。 */
    private static boolean loading;

    private static final List<Runnable> readyCallbacks = new ArrayList<>();
    private static final List<ErrorCallback> errorCallbacks = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 模型加载失败回调。 */
    public interface ErrorCallback {
        void onError(IOException e);
    }

    private VoskEngine() {
        // 工具类，禁止实例化
    }

    /** 模型是否已就绪。 */
    public static boolean isReady() {
        return model != null;
    }

    /**
     * 确保模型已加载：未加载则从 assets 解压（assets 目录名 = 模型目录名）。
     *
     * @param ctx        任意 Context（内部会取 ApplicationContext）
     * @param assetDir   assets 中模型目录名，例如 "vosk-model-small-cn"
     * @param onReady    模型就绪回调（主线程）
     * @param onError    加载失败回调（主线程）
     */
    public static synchronized void ensureModel(Context ctx, String assetDir,
                                                Runnable onReady, ErrorCallback onError) {
        if (model != null) {
            MAIN.post(onReady);
            return;
        }
        readyCallbacks.add(onReady);
        errorCallbacks.add(onError);
        if (loading) {
            return; // 已有人在加载，回调已排队，加载完成后统一触发
        }
        loading = true;
        StorageService.unpack(ctx.getApplicationContext(), assetDir, "vosk",
                m -> {
                    model = m;
                    loading = false;
                    fireReady();
                },
                e -> {
                    loading = false;
                    fireError(e);
                });
    }

    /**
     * 创建一个新的识别会话（每次录音用一个新的实例）。
     * 必须在 {@link #ensureModel} 成功之后调用。
     */
    public static SpeechService newService() throws IOException {
        if (model == null) {
            throw new IOException("语音模型未加载");
        }
        Recognizer recognizer = new Recognizer(model, SAMPLE_RATE);
        return new SpeechService(recognizer, SAMPLE_RATE);
    }

    /**
     * Create a raw Recognizer for smart listening (reads AudioRecord directly:
     * recognizes only when there is voice, keeps waiting otherwise).
     * Must be called after {@link #ensureModel} succeeds.
     */
    public static Recognizer newRecognizer() throws IOException {
        if (model == null) {
            throw new IOException("语音模型未加载");
        }
        return new Recognizer(model, SAMPLE_RATE);
    }

    private static void fireReady() {
        List<Runnable> list = new ArrayList<>(readyCallbacks);
        readyCallbacks.clear();
        errorCallbacks.clear();
        for (Runnable r : list) {
            MAIN.post(r);
        }
    }

    private static void fireError(IOException e) {
        List<ErrorCallback> list = new ArrayList<>(errorCallbacks);
        readyCallbacks.clear();
        errorCallbacks.clear();
        for (ErrorCallback cb : list) {
            MAIN.post(() -> cb.onError(e));
        }
    }
}
