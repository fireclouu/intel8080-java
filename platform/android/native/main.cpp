// dear imgui: standalone example application for Android + OpenGL ES 3

// Learn about Dear ImGui:
// - FAQ                  https://dearimgui.com/faq
// - Getting Started      https://dearimgui.com/getting-started
// - Documentation        https://dearimgui.com/docs (same as your local docs/ folder).
// - Introduction, links and more at the top of imgui.cpp

#include "imgui.h"
#include "imgui_impl_android.h"
#include "imgui_impl_opengl3.h"
#include "imgui_memory_editor.h"
#include <android/log.h>
#include <android_native_app_glue.h>
#include <android/asset_manager.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <string>
#include <thread>

#include <android/native_window_jni.h>

// Guest display, stored rotated 90deg left like the original cabinet
static const int            GUEST_WIDTH = 224;
static const int            GUEST_HEIGHT = 256;
static const int            VRAM_SIZE = GUEST_WIDTH * GUEST_HEIGHT / 8;
static const ImU32          COLOR_TOP = IM_COL32(0xFF, 0x1E, 0x00, 0xFF);
static const ImU32          COLOR_MIDDLE = IM_COL32(0xE8, 0xF9, 0xFD, 0xFF);
static const ImU32          COLOR_BELOW = IM_COL32(0x59, 0xCE, 0x8F, 0xFF);
static const ImU32          COLOR_BACKGROUND = IM_COL32(0x00, 0x00, 0x00, 0x00);

// Data
static GLuint               g_TextureId = 0;
static ImU32                g_Pixels[GUEST_WIDTH * GUEST_HEIGHT];

static EGLDisplay           g_EglDisplay = EGL_NO_DISPLAY;
static EGLSurface           g_EglSurface = EGL_NO_SURFACE;
static EGLContext           g_EglContext = EGL_NO_CONTEXT;
static struct android_app*  g_App = nullptr;
static bool                 g_Initialized = false;
static char                 g_LogTag[] = "ImGuiExample";
static std::string          g_IniFilename = "";
static bool                 g_Debugging = false;
static uint16_t              int_pc = 0;
static uint16_t              int_sp = 0;
static uint16_t              int_bc = 0;
static uint16_t              int_de = 0;
static uint16_t              int_hl = 0;
static uint8_t               int_a = 0;
// guest address space snapshot, written from the emulator thread; a torn read only shows stale bytes
static uint8_t              g_MemoryView[0x10000];
static MemoryEditor         g_MemoryEditor;
static bool                 g_FollowPc = true;
ImVec2 displaySize;

// Forward declarations of helper functions
static void Init(struct android_app* app);
static void Shutdown();
static void RenderLogsViaImGui();
static void RenderMemoryView();
static void MainLoopStep(float scale);
static int ShowSoftKeyboardInput();
static int PollUnicodeChars();
static int GetAssetData(const char* filename, void** out_data);

static void handleAppCmd(struct android_app* app, int32_t appCmd)
{
    switch (appCmd)
    {
        case APP_CMD_SAVE_STATE:
            break;
        case APP_CMD_INIT_WINDOW:
            Init(app);
            break;
        case APP_CMD_TERM_WINDOW:
            Shutdown();
            break;
        case APP_CMD_GAINED_FOCUS:
        case APP_CMD_LOST_FOCUS:
            break;
    }
}

static int32_t handleInputEvent(struct android_app* app, AInputEvent* inputEvent)
{
    return ImGui_ImplAndroid_HandleInputEvent(inputEvent);
}

void android_main(struct android_app* app)
{
    app->onAppCmd = handleAppCmd;
    app->onInputEvent = handleInputEvent;

    while (true)
    {
        int out_events;
        struct android_poll_source* out_data;

        // Poll all events. If the app is not visible, this loop blocks until g_Initialized == true.
        while (ALooper_pollAll(g_Initialized ? 0 : -1, nullptr, &out_events, (void**)&out_data) >= 0)
        {
            // Process one event
            if (out_data != nullptr)
                out_data->process(app, out_data);

            // Exit the app by returning from within the infinite loop
            if (app->destroyRequested != 0)
            {
                // shutdown() should have been called already while processing the
                // app command APP_CMD_TERM_WINDOW. But we play save here
                if (!g_Initialized)
                    Shutdown();

                return;
            }
        }
    }
}

void Init(struct android_app* app)
{
    if (g_Initialized)
        return;

    g_App = app;
    ANativeWindow_acquire(g_App->window);

    // Initialize EGL
    // This is mostly boilerplate code for EGL...
    {
        g_EglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (g_EglDisplay == EGL_NO_DISPLAY)
            __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "%s", "eglGetDisplay(EGL_DEFAULT_DISPLAY) returned EGL_NO_DISPLAY");

        if (eglInitialize(g_EglDisplay, 0, 0) != EGL_TRUE)
            __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "%s", "eglInitialize() returned with an error");

        const EGLint egl_attributes[] = { EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_NONE };
        EGLint num_configs = 0;
        if (eglChooseConfig(g_EglDisplay, egl_attributes, nullptr, 0, &num_configs) != EGL_TRUE)
            __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "%s", "eglChooseConfig() returned with an error");
        if (num_configs == 0)
            __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "%s", "eglChooseConfig() returned 0 matching config");

        // Get the first matching config
        EGLConfig egl_config;
        eglChooseConfig(g_EglDisplay, egl_attributes, &egl_config, 1, &num_configs);
        EGLint egl_format;
        eglGetConfigAttrib(g_EglDisplay, egl_config, EGL_NATIVE_VISUAL_ID, &egl_format);
        ANativeWindow_setBuffersGeometry(g_App->window, 0, 0, egl_format);

        const EGLint egl_context_attributes[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
        g_EglContext = eglCreateContext(g_EglDisplay, egl_config, EGL_NO_CONTEXT, egl_context_attributes);

        if (g_EglContext == EGL_NO_CONTEXT)
            __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "%s", "eglCreateContext() returned EGL_NO_CONTEXT");

        g_EglSurface = eglCreateWindowSurface(g_EglDisplay, egl_config, g_App->window, nullptr);
        eglMakeCurrent(g_EglDisplay, g_EglSurface, g_EglSurface, g_EglContext);
    }

    // Setup Dear ImGui context
    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();

    // Redirect loading/saving of .ini file to our location.
    // Make sure 'g_IniFilename' persists while we use Dear ImGui.
//    g_IniFilename = std::string(app->activity->internalDataPath) + "/imgui.ini";
    io.IniFilename = g_IniFilename.c_str();;

    // Setup Dear ImGui style
    ImGui::StyleColorsDark();
//    ImGui::StyleColorsLight();

    // Setup Platform/Renderer backends
    ImGui_ImplAndroid_Init(g_App->window);
    ImGui_ImplOpenGL3_Init("#version 300 es");

    // Load Fonts
    // - If no fonts are loaded, dear imgui will use the default font. You can also load multiple fonts and use ImGui::PushFont()/PopFont() to select them.
    // - If the file cannot be loaded, the function will return a nullptr. Please handle those errors in your application (e.g. use an assertion, or display an error and quit).
    // - The fonts will be rasterized at a given size (w/ oversampling) and stored into a texture when calling ImFontAtlas::Build()/GetTexDataAsXXXX(), which ImGui_ImplXXXX_NewFrame below will call.
    // - Read 'docs/FONTS.md' for more instructions and details.
    // - Remember that in C/C++ if you want to include a backslash \ in a string literal you need to write a double backslash \\ !
    // - Android: The TTF files have to be placed into the assets/ directory (android/app/src/main/assets), we use our GetAssetData() helper to retrieve them.

    // We load the default font with increased size to improve readability on many devices with "high" DPI.
    // FIXME: Put some effort into DPI awareness.
    // Important: when calling AddFontFromMemoryTTF(), ownership of font_data is transferred by Dear ImGui by default (deleted is handled by Dear ImGui), unless we set FontDataOwnedByAtlas=false in ImFontConfig
    ImFontConfig font_cfg;
    font_cfg.SizePixels = 22.0f;
    io.Fonts->AddFontDefault(&font_cfg);


    // Arbitrary scale-up
    // FIXME: Put some effort into DPI awareness
    ImGui::GetStyle().ScaleAllSizes(3.0f);

    displaySize = ImGui::GetIO().DisplaySize;
    g_Initialized = true;
}

// The texture lives as long as the GL context, it is only re-uploaded per frame
static void UploadFrame(const uint8_t* vram)
{
    // vram is 224 scanlines of 32 bytes, lsb first; scanline -> column, bit -> row from the bottom
    for (int i = 0; i < VRAM_SIZE; i++)
    {
        int x = i / 32;
        int y = 31 - (i % 32);
        ImU32 color = y < 8 ? COLOR_TOP : (y < 24 ? COLOR_MIDDLE : COLOR_BELOW);
        uint8_t data = vram[i];
        for (int bit = 0; bit < 8; bit++)
        {
            int row = (y * 8) + (7 - bit);
            g_Pixels[(row * GUEST_WIDTH) + x] = ((data >> bit) & 1) ? color : COLOR_BACKGROUND;
        }
    }

    if (g_TextureId == 0)
    {
        glGenTextures(1, &g_TextureId);
        glBindTexture(GL_TEXTURE_2D, g_TextureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, GUEST_WIDTH, GUEST_HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE, g_Pixels);
    }
    else
    {
        glBindTexture(GL_TEXTURE_2D, g_TextureId);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, GUEST_WIDTH, GUEST_HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, g_Pixels);
    }
    glBindTexture(GL_TEXTURE_2D, 0);
}

void MainLoopStep(float scale)
{
    ImGuiIO& io = ImGui::GetIO();
    if (g_EglDisplay == EGL_NO_DISPLAY)
        return;

    // Our state
    // (we use static, which essentially makes the variable globals, as a convenience to keep the example code easy to follow)
//    static ImVec4 clear_color = ImVec4(0.45f, 0.55f, 0.60f, 1.00f);

    // Poll Unicode characters via JNI
    // FIXME: do not call this every frame because of JNI overhead
//    PollUnicodeChars();

    // Open on-screen (soft) input if requested by Dear ImGui
//    static bool WantTextInputLast = false;
//    if (io.WantTextInput && !WantTextInputLast)
//        ShowSoftKeyboardInput();
//    WantTextInputLast = io.WantTextInput;

    // Start the Dear ImGui frame
    ImGui_ImplOpenGL3_NewFrame();
    ImGui_ImplAndroid_NewFrame();
    ImGui::NewFrame();

    ImGui::SetNextWindowPos(ImVec2(0, 0));
    ImGui::SetNextWindowSize(displaySize);
    ImGui::Begin("MainWindow", nullptr, ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoScrollbar);
    ImGui::Image((ImTextureID)(intptr_t)g_TextureId, ImVec2(GUEST_WIDTH * scale, GUEST_HEIGHT * scale));
    ImGui::End();

    // Logs
    RenderLogsViaImGui();

    // Rendering
    ImGui::Render();
    glViewport(0, 0, (int)io.DisplaySize.x, (int)io.DisplaySize.y);
    glClear(GL_COLOR_BUFFER_BIT);
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
    eglSwapBuffers(g_EglDisplay, g_EglSurface);
}

void Shutdown()
{
    if (!g_Initialized)
        return;

    // Cleanup
    if (g_TextureId != 0)
    {
        glDeleteTextures(1, &g_TextureId);
        g_TextureId = 0;
    }
    ImGui_ImplOpenGL3_Shutdown();
    ImGui_ImplAndroid_Shutdown();
    ImGui::DestroyContext();

    if (g_EglDisplay != EGL_NO_DISPLAY)
    {
        eglMakeCurrent(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

        if (g_EglContext != EGL_NO_CONTEXT)
            eglDestroyContext(g_EglDisplay, g_EglContext);

        if (g_EglSurface != EGL_NO_SURFACE)
            eglDestroySurface(g_EglDisplay, g_EglSurface);

        eglTerminate(g_EglDisplay);
    }

    g_EglDisplay = EGL_NO_DISPLAY;
    g_EglContext = EGL_NO_CONTEXT;
    g_EglSurface = EGL_NO_SURFACE;
    ANativeWindow_release(g_App->window);

    g_Initialized = false;
}

void RenderLogsViaImGui() {
    if (!g_Debugging) return;
    static float f = 0.0f;
    static int counter = 0;
    ImGuiIO& io = ImGui::GetIO();
    ImGui::SetNextWindowSize(ImVec2(0, 0), ImGuiCond_Always);
    ImGui::Begin("Memory View | intel 8080 CPU", &g_Debugging);

    ImGui::Spacing();

    ImGui::Text("PC: %04X  SP: %04X", int_pc, int_sp);
    ImGui::Text("BC: %04X  DE: %04X  HL: %04X", int_bc, int_de, int_hl);
    ImGui::Text("A: %02X", int_a);
//    ImGui::Button("Edit");

    ImGui::Spacing();
    ImGui::Spacing();

//        ImGui::Text("Disassembler");
//        ImGui::Text("0000  00  NOP");
//        ImGui::Text("0001  C3  JMP  $0DFE");
//        ImGui::Text("0004  00  NOP");

    ImGui::Spacing();

    ImGui::Text("%.3f ms/frame (%.1f FPS)", 1000.0f / io.Framerate, io.Framerate);

    ImGui::Spacing();
    ImGui::Spacing();

    ImGui::Text("written in Java, NDK for ImGUI");
    ImGui::Text("Display:\nSurfaceView -> nativeWindow / OpenGL");

    ImGui::Spacing();
    ImGui::Text("- fireclouu");

    ImGui::End();

    RenderMemoryView();
}

// Hex view of the program being run, current pc highlighted
static void RenderMemoryView()
{
    g_MemoryEditor.ReadOnly = true;
    g_MemoryEditor.HighlightMin = int_pc;
    g_MemoryEditor.HighlightMax = int_pc + 1;

    MemoryEditor::Sizes s;
    g_MemoryEditor.CalcSizes(s, sizeof(g_MemoryView), 0x0000);
    ImGui::SetNextWindowSize(ImVec2(s.WindowWidth, s.WindowWidth * 0.60f), ImGuiCond_FirstUseEver);
    if (ImGui::Begin("Hex View | memory", nullptr, ImGuiWindowFlags_NoScrollbar))
    {
        ImGui::Checkbox("Follow PC", &g_FollowPc);
        g_MemoryEditor.DrawContents(g_MemoryView, sizeof(g_MemoryView));

        // GotoAddrAndHighlight() would also grab focus every frame and fight touch input,
        // so scroll the editor's child window directly, the same way it does internally
        if (g_FollowPc)
        {
            ImGui::BeginChild("##scrolling");
            float pcRowY = (float)(int_pc / g_MemoryEditor.Cols) * ImGui::GetTextLineHeight();
            ImGui::SetScrollY(pcRowY - ImGui::GetWindowHeight() * 0.5f);
            ImGui::EndChild();
        }
    }
    ImGui::End();
}
// Helper functions

// Unfortunately, there is no way to show the on-screen input from native code.
// Therefore, we call ShowSoftKeyboardInput() of the main activity implemented in MainActivity.kt via JNI.
static int ShowSoftKeyboardInput()
{
    JavaVM* java_vm = g_App->activity->vm;
    JNIEnv* java_env = nullptr;

    jint jni_return = java_vm->GetEnv((void**)&java_env, JNI_VERSION_1_6);
    if (jni_return == JNI_ERR)
        return -1;

    jni_return = java_vm->AttachCurrentThread(&java_env, nullptr);
    if (jni_return != JNI_OK)
        return -2;

    jclass native_activity_clazz = java_env->GetObjectClass(g_App->activity->clazz);
    if (native_activity_clazz == nullptr)
        return -3;

    jmethodID method_id = java_env->GetMethodID(native_activity_clazz, "showSoftInput", "()V");
    if (method_id == nullptr)
        return -4;

    java_env->CallVoidMethod(g_App->activity->clazz, method_id);

    jni_return = java_vm->DetachCurrentThread();
    if (jni_return != JNI_OK)
        return -5;

    return 0;
}

// Unfortunately, the native KeyEvent implementation has no getUnicodeChar() function.
// Therefore, we implement the processing of KeyEvents in MainActivity.kt and poll
// the resulting Unicode characters here via JNI and send them to Dear ImGui.
static int PollUnicodeChars()
{
    JavaVM* java_vm = g_App->activity->vm;
    JNIEnv* java_env = nullptr;

    jint jni_return = java_vm->GetEnv((void**)&java_env, JNI_VERSION_1_6);
    if (jni_return == JNI_ERR)
        return -1;

    jni_return = java_vm->AttachCurrentThread(&java_env, nullptr);
    if (jni_return != JNI_OK)
        return -2;

    jclass native_activity_clazz = java_env->GetObjectClass(g_App->activity->clazz);
    if (native_activity_clazz == nullptr)
        return -3;

    jmethodID method_id = java_env->GetMethodID(native_activity_clazz, "pollUnicodeChar", "()I");
    if (method_id == nullptr)
        return -4;

    // Send the actual characters to Dear ImGui
    ImGuiIO& io = ImGui::GetIO();
    jint unicode_character;
    while ((unicode_character = java_env->CallIntMethod(g_App->activity->clazz, method_id)) != 0)
        io.AddInputCharacter(unicode_character);

    jni_return = java_vm->DetachCurrentThread();
    if (jni_return != JNI_OK)
        return -5;

    return 0;
}

// Helper to retrieve data placed into the assets/ directory (android/app/src/main/assets)
static int GetAssetData(const char* filename, void** outData)
{
    int num_bytes = 0;
    AAsset* asset_descriptor = AAssetManager_open(g_App->activity->assetManager, filename, AASSET_MODE_BUFFER);
    if (asset_descriptor)
    {
        num_bytes = AAsset_getLength(asset_descriptor);
        *outData = IM_ALLOC(num_bytes);
        int64_t num_bytes_read = AAsset_read(asset_descriptor, *outData, num_bytes);
        AAsset_close(asset_descriptor);
        IM_ASSERT(num_bytes_read == num_bytes);
    }
    return num_bytes;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_fireclouu_intel8080emu_DisplaySurface_nativeInit(JNIEnv *env, jobject thiz, jobject surface) {
    // surfaceChanged() calls this again, don't leak another app/window reference
    if (g_Initialized) return 0;
    auto *pseudoApp = new android_app();
    g_App = pseudoApp;
    pseudoApp->window = ANativeWindow_fromSurface(env, surface);
    Init(pseudoApp);
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_fireclouu_intel8080emu_DisplaySurface_nativeMainLoopStep(JNIEnv *env, jobject thiz, jbyteArray memoryVideoRam, jfloat scale) {
    if (!g_Initialized) return;
    if (env->GetArrayLength(memoryVideoRam) < VRAM_SIZE) return;

    static uint8_t vram[VRAM_SIZE];
    env->GetByteArrayRegion(memoryVideoRam, 0, VRAM_SIZE, (jbyte*) vram);
    UploadFrame(vram);
    MainLoopStep(scale);
}

// The app runs a regular Activity, not android_main(), so touches arrive from DisplaySurface
extern "C"
JNIEXPORT void JNICALL
Java_com_fireclouu_intel8080emu_DisplaySurface_nativeTouchEvent(JNIEnv *env, jobject thiz, jint action, jfloat x, jfloat y) {
    if (!g_Initialized) return;

    ImGuiIO& io = ImGui::GetIO();
    io.AddMouseSourceEvent(ImGuiMouseSource_TouchScreen);
    io.AddMousePosEvent(x, y);
    switch (action)
    {
        case AMOTION_EVENT_ACTION_DOWN:
            io.AddMouseButtonEvent(0, true);
            break;
        case AMOTION_EVENT_ACTION_UP:
        case AMOTION_EVENT_ACTION_CANCEL:
            io.AddMouseButtonEvent(0, false);
            break;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_fireclouu_intel8080emu_DisplaySurface_nativeShutdown(JNIEnv *env, jobject thiz) {
    Shutdown();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_fireclouu_intel8080emu_AndroidPlatform_nativeSetMemory(JNIEnv *env, jobject thiz, jint pc, jint sp, jint bc, jint de, jint hl, jint a) {
    int_pc = pc;
    int_sp = sp;
    int_bc = bc;
    int_de = de;
    int_hl = hl;
    int_a = a;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_fireclouu_intel8080emu_AndroidPlatform_nativeSetDebugging(JNIEnv *env, jobject thiz, jboolean debugging) {
    g_Debugging = debugging;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_fireclouu_intel8080emu_AndroidPlatform_nativeSetMemoryView(JNIEnv *env, jobject thiz, jbyteArray memory) {
    jsize length = env->GetArrayLength(memory);
    if (length > (jsize) sizeof(g_MemoryView)) length = sizeof(g_MemoryView);
    env->GetByteArrayRegion(memory, 0, length, (jbyte*) g_MemoryView);
}
