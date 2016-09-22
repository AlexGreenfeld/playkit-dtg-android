/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kaltura.android.exoplayer;

import com.kaltura.android.exoplayer.util.Assertions;
import com.kaltura.android.exoplayer.util.MimeTypes;
import com.kaltura.android.exoplayer.util.Util;

import android.annotation.TargetApi;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaCodecList;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A utility class for querying the available codecs.
 */
@TargetApi(16)
public final class MediaCodecUtil {

  /**
   * Thrown when an error occurs querying the device for its underlying media capabilities.
   * <p>
   * Such failures are not expected in normal operation and are normally temporary (e.g. if the
   * mediaserver process has crashed and is yet to restart).
   */
  public static class DecoderQueryException extends IOException {

    private DecoderQueryException(Throwable cause) {
      super("Failed to query underlying media codecs", cause);
    }

  }

  private static final String TAG = "MediaCodecUtil";

  private static final Map<CodecKey, List<DecoderInfo>> decoderInfosCache = new HashMap<>();

  // Lazily initialized.
  private static int maxH264DecodableFrameSize = -1;

  private MediaCodecUtil() {}

  /**
   * Get information about the decoder that will be used for a given mime type.
   *
   * @param mimeType The mime type.
   * @param secure Whether the decoder is required to support secure decryption. Always pass false
   *     unless secure decryption really is required.
   * @return Information about the decoder that will be used, or null if no decoder exists.
   */
  public static DecoderInfo getDecoderInfo(String mimeType, boolean secure)
      throws DecoderQueryException {
    List<DecoderInfo> decoderInfos = getDecoderInfos(mimeType, secure);
    return decoderInfos.isEmpty() ? null : decoderInfos.get(0);
  }

  /**
   * Optional call to warm the codec cache for a given mime type.
   * <p>
   * Calling this method may speed up subsequent calls to {@link #getDecoderInfo(String, boolean)}.
   *
   * @param mimeType The mime type.
   * @param secure Whether the decoder is required to support secure decryption. Always pass false
   *     unless secure decryption really is required.
   */
  public static void warmCodec(String mimeType, boolean secure) {
    try {
      getDecoderInfos(mimeType, secure);
    } catch (DecoderQueryException e) {
      // Codec warming is best effort, so we can swallow the exception.
      Log.e(TAG, "Codec warming failed", e);
    }
  }

  /**
   * Returns all @{link DecoderInfo}s for the given mime type, in the order given by MediaCodecList.
   *
   * @param mimeType The mime type.
   * @param secure Whether the decoder is required to support secure decryption. Always pass false
   *     unless secure decryption really is required.
   * @return A list of all @{link DecoderInfo}s for the given mime type,
   */
  public static synchronized List<DecoderInfo> getDecoderInfos(String mimeType, boolean secure)
      throws DecoderQueryException {
    CodecKey key = new CodecKey(mimeType, secure);
    List<DecoderInfo> decoderInfos = decoderInfosCache.get(key);
    if (decoderInfos != null) {
      return decoderInfos;
    }
    MediaCodecListCompat mediaCodecList = Util.SDK_INT >= 21
        ? new MediaCodecListCompatV21(secure) : new MediaCodecListCompatV16();
    decoderInfos = getDecoderInfosInternal(key, mediaCodecList);
    if (secure && decoderInfos.isEmpty() && 21 <= Util.SDK_INT && Util.SDK_INT <= 23) {
      // Some devices don't list secure decoders on API level 21 [Internal: b/18678462]. Try the
      // legacy path. We also try this path on API levels 22 and 23 as a defensive measure.
      // TODO: Verify that the issue cannot occur on API levels 22 and 23, and tighten this block
      // to execute on API level 21 only if confirmed.
      mediaCodecList = new MediaCodecListCompatV16();
      decoderInfos = getDecoderInfosInternal(key, mediaCodecList);
      if (!decoderInfos.isEmpty()) {
        Log.w(TAG, "MediaCodecList API didn't list secure decoder for: " + mimeType
            + ". Assuming: " + decoderInfos.get(0).name);
      }
    }
    decoderInfos = Collections.unmodifiableList(decoderInfos);
    decoderInfosCache.put(key, decoderInfos);
    return decoderInfos;
  }

  private static List<DecoderInfo> getDecoderInfosInternal(
      CodecKey key, MediaCodecListCompat mediaCodecList) throws DecoderQueryException {
    try {
      List<DecoderInfo> decoderInfos = new ArrayList<>();
      String mimeType = key.mimeType;
      int numberOfCodecs = mediaCodecList.getCodecCount();
      boolean secureDecodersExplicit = mediaCodecList.secureDecodersExplicit();
      // Note: MediaCodecList is sorted by the framework such that the best decoders come first.
      for (int i = 0; i < numberOfCodecs; i++) {
        MediaCodecInfo codecInfo = mediaCodecList.getCodecInfoAt(i);
        String codecName = codecInfo.getName();
        if (isCodecUsableDecoder(codecInfo, codecName, secureDecodersExplicit)) {
          for (String supportedType : codecInfo.getSupportedTypes()) {
            if (supportedType.equalsIgnoreCase(mimeType)) {
              CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(supportedType);
              boolean secure = mediaCodecList.isSecurePlaybackSupported(mimeType, capabilities);
              if ((secureDecodersExplicit && key.secure == secure)
                  || (!secureDecodersExplicit && !key.secure)) {
                decoderInfos.add(new DecoderInfo(codecName, capabilities));
              } else if (!secureDecodersExplicit && secure) {
                decoderInfos.add(new DecoderInfo(codecName + ".secure", capabilities));
                // It only makes sense to have one synthesized secure decoder, return immediately.
                return decoderInfos;
              }
            }
          }
        }
      }
      return decoderInfos;
    } catch (Exception e) {
      // If the underlying mediaserver is in a bad state, we may catch an IllegalStateException
      // or an IllegalArgumentException here.
      throw new DecoderQueryException(e);
    }
  }

  /**
   * Returns whether the specified codec is usable for decoding on the current device.
   */
  private static boolean isCodecUsableDecoder(MediaCodecInfo info, String name,
      boolean secureDecodersExplicit) {
    if (info.isEncoder() || (!secureDecodersExplicit && name.endsWith(".secure"))) {
      return false;
    }

    // Work around broken audio decoders.
    if (Util.SDK_INT < 21
        && ("CIPAACDecoder".equals(name))
            || "CIPMP3Decoder".equals(name)
            || "CIPVorbisDecoder".equals(name)
            || "AACDecoder".equals(name)
            || "MP3Decoder".equals(name)) {
      return false;
    }
    if (Util.SDK_INT < 18 && "OMX.SEC.MP3.Decoder".equals(name)) {
      return false;
    }

    // Work around an issue where creating a particular MP3 decoder on some devices on platform API
    // version 16 crashes mediaserver.
    if (Util.SDK_INT == 16
        && "OMX.qcom.audio.decoder.mp3".equals(name)
        && ("dlxu".equals(Util.DEVICE) // HTC Butterfly
            || "protou".equals(Util.DEVICE) // HTC Desire X
            || "C6602".equals(Util.DEVICE) // Sony Xperia Z
            || "C6603".equals(Util.DEVICE)
            || "C6606".equals(Util.DEVICE)
            || "C6616".equals(Util.DEVICE)
            || "L36h".equals(Util.DEVICE)
            || "SO-02E".equals(Util.DEVICE))) {
      return false;
    }

    // Work around an issue where large timestamps are not propagated correctly.
    if (Util.SDK_INT == 16
        && "OMX.qcom.audio.decoder.aac".equals(name)
        && ("C1504".equals(Util.DEVICE) // Sony Xperia E
            || "C1505".equals(Util.DEVICE)
            || "C1604".equals(Util.DEVICE) // Sony Xperia E dual
            || "C1605".equals(Util.DEVICE))) {
      return false;
    }

    // Work around an issue where the VP8 decoder on Samsung Galaxy S3/S4 Mini does not render
    // video.
    if (Util.SDK_INT <= 19 && Util.DEVICE != null
        && (Util.DEVICE.startsWith("d2") || Util.DEVICE.startsWith("serrano"))
        && "samsung".equals(Util.MANUFACTURER) && name.equals("OMX.SEC.vp8.dec")) {
      return false;
    }

    return true;
  }

  /**
   * Tests whether the device advertises it can decode video of a given type at a specified width
   * and height.
   * <p>
   * Must not be called if the device SDK version is less than 21.
   *
   * @param mimeType The mime type.
   * @param secure Whether the decoder is required to support secure decryption. Always pass false
   *     unless secure decryption really is required.
   * @param width Width in pixels.
   * @param height Height in pixels.
   * @return Whether the decoder advertises support of the given size.
   */
  @TargetApi(21)
  public static boolean isSizeSupportedV21(String mimeType, boolean secure, int width,
      int height) throws DecoderQueryException {
    Assertions.checkState(Util.SDK_INT >= 21);
    MediaCodecInfo.VideoCapabilities videoCapabilities = getVideoCapabilitiesV21(mimeType, secure);
    return videoCapabilities != null && videoCapabilities.isSizeSupported(width, height);
  }

  /**
   * Tests whether the device advertises it can decode video of a given type at a specified
   * width, height, and frame rate.
   * <p>
   * Must not be called if the device SDK version is less than 21.
   *
   * @param mimeType The mime type.
   * @param secure Whether the decoder is required to support secure decryption. Always pass false
   *     unless secure decryption really is required.
   * @param width Width in pixels.
   * @param height Height in pixels.
   * @param frameRate Frame rate in frames per second.
   * @return Whether the decoder advertises support of the given size and frame rate.
   */
  @TargetApi(21)
  public static boolean isSizeAndRateSupportedV21(String mimeType, boolean secure,
      int width, int height, double frameRate) throws DecoderQueryException {
    Assertions.checkState(Util.SDK_INT >= 21);
    MediaCodecInfo.VideoCapabilities videoCapabilities = getVideoCapabilitiesV21(mimeType, secure);
    return videoCapabilities != null
        && videoCapabilities.areSizeAndRateSupported(width, height, frameRate);
  }

  /**
   * @param profile An AVC profile constant from {@link CodecProfileLevel}.
   * @param level An AVC profile level from {@link CodecProfileLevel}.
   * @return Whether the specified profile is supported at the specified level.
   * @deprecated Prefer {@link #getDecoderInfos(String, boolean)} for new code.
   */
  @Deprecated
  public static boolean isH264ProfileSupported(int profile, int level)
      throws DecoderQueryException {
    DecoderInfo decoderInfo = getDecoderInfo(MimeTypes.VIDEO_H264, false);
    if (decoderInfo == null) {
      return false;
    }
    for (CodecProfileLevel profileLevel : decoderInfo.capabilities.profileLevels) {
      if (profileLevel.profile == profile && profileLevel.level >= level) {
        return true;
      }
    }
    return false;
  }

  /**
   * @return the maximum frame size for an H264 stream that can be decoded on the device.
   */
  public static int maxH264DecodableFrameSize() throws DecoderQueryException {
    if (maxH264DecodableFrameSize == -1) {
      int result = 0;
      DecoderInfo decoderInfo = getDecoderInfo(MimeTypes.VIDEO_H264, false);
      if (decoderInfo != null) {
        for (CodecProfileLevel profileLevel : decoderInfo.capabilities.profileLevels) {
          result = Math.max(avcLevelToMaxFrameSize(profileLevel.level), result);
        }
      }
      maxH264DecodableFrameSize = result;
    }
    return maxH264DecodableFrameSize;
  }

  @TargetApi(21)
  private static MediaCodecInfo.VideoCapabilities getVideoCapabilitiesV21(String mimeType,
      boolean secure) throws DecoderQueryException {
    DecoderInfo decoderInfo = getDecoderInfo(mimeType, secure);
    return decoderInfo == null ? null : decoderInfo.capabilities.getVideoCapabilities();
  }

  /**
   * Conversion values taken from ISO 14496-10 Table A-1.
   *
   * @param avcLevel one of CodecProfileLevel.AVCLevel* constants.
   * @return maximum frame size that can be decoded by a decoder with the specified avc level
   *      (or {@code -1} if the level is not recognized)
   */
  private static int avcLevelToMaxFrameSize(int avcLevel) {
    switch (avcLevel) {
      case CodecProfileLevel.AVCLevel1: return 99 * 16 * 16;
      case CodecProfileLevel.AVCLevel1b: return 99 * 16 * 16;
      case CodecProfileLevel.AVCLevel12: return 396 * 16 * 16;
      case CodecProfileLevel.AVCLevel13: return 396 * 16 * 16;
      case CodecProfileLevel.AVCLevel2: return 396 * 16 * 16;
      case CodecProfileLevel.AVCLevel21: return 792 * 16 * 16;
      case CodecProfileLevel.AVCLevel22: return 1620 * 16 * 16;
      case CodecProfileLevel.AVCLevel3: return 1620 * 16 * 16;
      case CodecProfileLevel.AVCLevel31: return 3600 * 16 * 16;
      case CodecProfileLevel.AVCLevel32: return 5120 * 16 * 16;
      case CodecProfileLevel.AVCLevel4: return 8192 * 16 * 16;
      case CodecProfileLevel.AVCLevel41: return 8192 * 16 * 16;
      case CodecProfileLevel.AVCLevel42: return 8704 * 16 * 16;
      case CodecProfileLevel.AVCLevel5: return 22080 * 16 * 16;
      case CodecProfileLevel.AVCLevel51: return 36864 * 16 * 16;
      default: return -1;
    }
  }

  private interface MediaCodecListCompat {

    /**
     * The number of codecs in the list.
     */
    int getCodecCount();

    /**
     * The info at the specified index in the list.
     *
     * @param index The index.
     */
    MediaCodecInfo getCodecInfoAt(int index);

    /**
     * @return Returns whether secure decoders are explicitly listed, if present.
     */
    boolean secureDecodersExplicit();

    /**
     * Whether secure playback is supported for the given {@link CodecCapabilities}, which should
     * have been obtained from a {@link MediaCodecInfo} obtained from this list.
     */
    boolean isSecurePlaybackSupported(String mimeType, CodecCapabilities capabilities);

  }

  @TargetApi(21)
  private static final class MediaCodecListCompatV21 implements MediaCodecListCompat {

    private final int codecKind;

    private MediaCodecInfo[] mediaCodecInfos;

    public MediaCodecListCompatV21(boolean includeSecure) {
      codecKind = includeSecure ? MediaCodecList.ALL_CODECS : MediaCodecList.REGULAR_CODECS;
    }

    @Override
    public int getCodecCount() {
      ensureMediaCodecInfosInitialized();
      return mediaCodecInfos.length;
    }

    @Override
    public MediaCodecInfo getCodecInfoAt(int index) {
      ensureMediaCodecInfosInitialized();
      return mediaCodecInfos[index];
    }

    @Override
    public boolean secureDecodersExplicit() {
      return true;
    }

    @Override
    public boolean isSecurePlaybackSupported(String mimeType, CodecCapabilities capabilities) {
      return capabilities.isFeatureSupported(CodecCapabilities.FEATURE_SecurePlayback);
    }

    private void ensureMediaCodecInfosInitialized() {
      if (mediaCodecInfos == null) {
        mediaCodecInfos = new MediaCodecList(codecKind).getCodecInfos();
      }
    }

  }

  @SuppressWarnings("deprecation")
  private static final class MediaCodecListCompatV16 implements MediaCodecListCompat {

    @Override
    public int getCodecCount() {
      return MediaCodecList.getCodecCount();
    }

    @Override
    public MediaCodecInfo getCodecInfoAt(int index) {
      return MediaCodecList.getCodecInfoAt(index);
    }

    @Override
    public boolean secureDecodersExplicit() {
      return false;
    }

    @Override
    public boolean isSecurePlaybackSupported(String mimeType, CodecCapabilities capabilities) {
      // Secure decoders weren't explicitly listed prior to API level 21. We assume that a secure
      // H264 decoder exists.
      return MimeTypes.VIDEO_H264.equals(mimeType);
    }

  }

  private static final class CodecKey {

    public final String mimeType;
    public final boolean secure;

    public CodecKey(String mimeType, boolean secure) {
      this.mimeType = mimeType;
      this.secure = secure;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((mimeType == null) ? 0 : mimeType.hashCode());
      result = prime * result + (secure ? 1231 : 1237);
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null || obj.getClass() != CodecKey.class) {
        return false;
      }
      CodecKey other = (CodecKey) obj;
      return TextUtils.equals(mimeType, other.mimeType) && secure == other.secure;
    }

  }

}
