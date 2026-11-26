JNI_ROOT := $(call my-dir)

# SDL_mixer: só o necessário para efeitos sonoros (sem libs externas)
SUPPORT_WAVPACK := false
SUPPORT_MOD_XMP := false
SUPPORT_MID_TIMIDITY := false
SUPPORT_OGG := false
SUPPORT_MP3_MPG123 := false
SUPPORT_FLAC_LIBFLAC := false

include $(JNI_ROOT)/SDL/Android.mk
include $(JNI_ROOT)/SDL2_mixer/Android.mk
include $(JNI_ROOT)/src/Android.mk
