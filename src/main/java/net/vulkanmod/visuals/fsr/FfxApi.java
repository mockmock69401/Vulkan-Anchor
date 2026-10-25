package net.vulkanmod.visuals.fsr;

import static org.lwjgl.system.JNI.invokePPI;
import static org.lwjgl.system.JNI.invokePPPI;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * FSR design 4.3: the five ffx API entry points of one loaded DLL. Every method takes the address of an
 * ffxContext (a void* slot the DLL fills on create) and returns the ffxReturnCode_t; allocation callbacks are NULL.
 * Not final so tests can record calls instead of reaching the DLL.
 */
public class FfxApi {
    public static final int RETURN_OK = 0;

    /** FfxApiReturnCodes in ffx_api.h, indexed by code. */
    private static final String[] RETURN_NAMES = {
            "OK", "ERROR", "ERROR_UNKNOWN_DESCTYPE", "ERROR_RUNTIME_ERROR", "NO_PROVIDER", "ERROR_MEMORY", "ERROR_PARAMETER"
    };

    private final long createContext;
    private final long destroyContext;
    private final long configure;
    private final long query;
    private final long dispatch;

    FfxApi(long createContext, long destroyContext, long configure, long query, long dispatch) {
        this.createContext = createContext;
        this.destroyContext = destroyContext;
        this.configure = configure;
        this.query = query;
        this.dispatch = dispatch;
    }

    public FfxApi(FsrNative dll) {
        this(dll.fn("ffxCreateContext"), dll.fn("ffxDestroyContext"), dll.fn("ffxConfigure"), dll.fn("ffxQuery"), dll.fn("ffxDispatch"));
    }

    /** ffxCreateContext(ffxContext*, ffxCreateContextDescHeader*, NULL). */
    public int createContext(long pContext, long desc) {
        return invokePPPI(pContext, desc, NULL, createContext);
    }

    /** ffxDestroyContext(ffxContext*, NULL). */
    public int destroyContext(long pContext) {
        return invokePPI(pContext, NULL, destroyContext);
    }

    /** ffxConfigure(ffxContext*, const ffxConfigureDescHeader*). */
    public int configure(long pContext, long desc) {
        return invokePPI(pContext, desc, configure);
    }

    /** ffxQuery(ffxContext*, ffxQueryDescHeader*). */
    public int query(long pContext, long desc) {
        return invokePPI(pContext, desc, query);
    }

    /** ffxDispatch(ffxContext*, const ffxDispatchDescHeader*). */
    public int dispatch(long pContext, long desc) {
        return invokePPI(pContext, desc, dispatch);
    }

    /** Throws an {@link FsrException} naming the call and the return code unless the code is {@link #RETURN_OK}. */
    public static void check(int code, String what) {
        if (code != RETURN_OK) {
            String name = code > 0 && code < RETURN_NAMES.length ? "FFX_API_RETURN_" + RETURN_NAMES[code] : "unknown ffx return code";
            throw new FsrException(what + " failed: " + name + " (" + code + ")");
        }
    }
}
