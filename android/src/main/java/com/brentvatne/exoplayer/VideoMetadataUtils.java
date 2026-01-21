package com.brentvatne.exoplayer;

import android.media.MediaCodecInfo.CodecProfileLevel;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.MimeTypes;

import java.util.Locale;

public class VideoMetadataUtils {

    public static @Nullable String getContainerDisplayString(@Nullable String containerMimeType) {
        if (TextUtils.isEmpty(containerMimeType)) {
            return "Unknown";
        }
        String name;
        switch (containerMimeType) {
            case MimeTypes.APPLICATION_M3U8: // application/x-mpegURL
            case "application/vnd.apple.mpegurl":
                name = "HLS";
                break;
            case MimeTypes.APPLICATION_MPD: // application/dash+xml
                name = "DASH";
                break;
            case "application/vnd.ms-sstr+xml":
                name = "Smooth Streaming";
                break;
            case MimeTypes.VIDEO_MP4:
            case MimeTypes.AUDIO_MP4:
            case MimeTypes.APPLICATION_MP4:
                name = "MP4";
                break;
            case MimeTypes.VIDEO_WEBM:
            case MimeTypes.AUDIO_WEBM:
                name = "WebM";
                break;
            case MimeTypes.VIDEO_MATROSKA:
                name = "Matroska (MKV)";
                break;
            case MimeTypes.VIDEO_MP2T:
                name = "MPEG-TS";
                break;
            case "video/quicktime":
                name = "QuickTime (MOV)";
                break;
            default:
                name = "Unknown";
                break;
        }
        return name + " (" + containerMimeType + ")";
    }

    public static @Nullable String getCodecDisplayString(@Nullable String sampleMimeType) {
        if (TextUtils.isEmpty(sampleMimeType)) {
            return null;
        }
        String name;
        switch (sampleMimeType) {
            case MimeTypes.VIDEO_H264:
                name = "H.264 (AVC)";
                break;
            case MimeTypes.VIDEO_H265:
                name = "HEVC (H.265)";
                break;
            case MimeTypes.VIDEO_DOLBY_VISION:
                name = "Dolby Vision";
                break;
            case MimeTypes.VIDEO_VP9:
                name = "VP9";
                break;
            case MimeTypes.VIDEO_AV1:
                name = "AV1";
                break;
            case MimeTypes.AUDIO_AAC:
                name = "AAC";
                break;
            case MimeTypes.AUDIO_AC3:
                name = "AC-3 (Dolby Digital)";
                break;
            case MimeTypes.AUDIO_E_AC3:
                name = "E-AC-3 (Dolby Digital Plus)";
                break;
            case MimeTypes.AUDIO_E_AC3_JOC:
                name = "Dolby Atmos (E-AC-3 JOC)";
                break;
            case MimeTypes.AUDIO_TRUEHD:
                name = "Dolby TrueHD";
                break;
            case MimeTypes.AUDIO_DTS:
                name = "DTS";
                break;
            case MimeTypes.AUDIO_DTS_HD:
                name = "DTS-HD";
                break;
            case MimeTypes.AUDIO_OPUS:
                name = "Opus";
                break;
            case MimeTypes.AUDIO_VORBIS:
                name = "Vorbis";
                break;
            case MimeTypes.AUDIO_MPEG:
                name = "MP3";
                break;
            case MimeTypes.AUDIO_FLAC:
                name = "FLAC";
                break;
            case MimeTypes.AUDIO_RAW:
                name = "PCM";
                break;
            default:
                name = "Unknown";
                break;
        }
        return name + " (" + sampleMimeType + ")";
    }

    public static @Nullable String getResolutionDisplayString(int width, int height) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        return width + "×" + height;
    }

    public static @Nullable String getFrameRateDisplayString(float frameRate) {
        if (frameRate <= 0f || Float.isNaN(frameRate)) {
            return null;
        }
        // 29.97, 59.94, etc.
        return String.format(Locale.US, "%.2f fps", frameRate);
    }

    public static @Nullable String getAudioLayoutDisplayString(int channelCount) {
        if (channelCount <= 0) {
            return null;
        }
        switch (channelCount) {
            case 1:
                return "Mono";
            case 2:
                return "Stereo";
            case 6:
                return "5.1";
            case 8:
                return "7.1";
            default:
                return channelCount + "ch";
        }
    }

    public static @Nullable String getAudioChannelsDisplayString(int channelCount) {
        if (channelCount <= 0) {
            return null;
        }
        String layout = getAudioLayoutDisplayString(channelCount);
        if (TextUtils.isEmpty(layout)) {
            return String.valueOf(channelCount);
        }
        return channelCount + " (" + layout + ")";
    }

    public static @Nullable String getCombinedBitrateDisplayString(int videoBitrate, int audioBitrate) {
        String v = formatBitrate(videoBitrate);
        String a = formatBitrate(audioBitrate);

        if (TextUtils.isEmpty(v) && TextUtils.isEmpty(a)) {
            return null;
        }
        if (!TextUtils.isEmpty(v) && !TextUtils.isEmpty(a)) {
            return "Video " + v + ", Audio " + a;
        }
        if (!TextUtils.isEmpty(v)) {
            return "Video " + v;
        }
        return "Audio " + a;
    }

    private static @Nullable String formatBitrate(int bitrate) {
        if (bitrate <= 0) {
            return null;
        }

        if (bitrate >= 1_000_000) {
            return String.format(Locale.US, "%.2f Mbps", bitrate / 1_000_000.0);
        }
        if (bitrate >= 1_000) {
            return String.format(Locale.US, "%.0f kbps", bitrate / 1_000.0);
        }
        return bitrate + " bps";
    }

    public static @Nullable String getDynamicRangeDisplayName(@Nullable String sampleMimeType, @Nullable ColorInfo colorInfo) {
        if (MimeTypes.VIDEO_DOLBY_VISION.equals(sampleMimeType)) {
            return "Dolby Vision";
        }

        // Most content is SDR; only label HDR when we have strong evidence.
        if (colorInfo == null) {
            return "SDR";
        }

        switch (colorInfo.colorTransfer) {
            case C.COLOR_TRANSFER_ST2084:
                return "HDR10 (PQ)";
            case C.COLOR_TRANSFER_HLG:
                return "HLG";
            case C.COLOR_TRANSFER_SDR:
            case C.INDEX_UNSET:
                return "SDR";
            default:
                // Unknown / device-specific values: don't over-report HDR.
                return "SDR";
        }
    }

    public static @Nullable String getCodecProfileLevelDisplayString(@Nullable String sampleMimeType, int profile, int level) {
        if (TextUtils.isEmpty(sampleMimeType)) {
            return null;
        }

        // Avoid emitting meaningless profile/level strings for audio.
        if (!sampleMimeType.startsWith("video/")) {
            return null;
        }

        if (MimeTypes.VIDEO_DOLBY_VISION.equals(sampleMimeType)) {
            Integer dvProfile = getDolbyVisionProfileNumber(profile);
            if (dvProfile != null) {
                return "Dolby Vision Profile " + dvProfile;
            }
            return "Dolby Vision Profile " + profile;
        }

        String profileName = getProfileName(sampleMimeType, profile);
        String levelName = getLevelName(sampleMimeType, level);
        if (!TextUtils.isEmpty(profileName) && !TextUtils.isEmpty(levelName)) {
            return profileName + "@" + levelName;
        }
        if (!TextUtils.isEmpty(profileName)) {
            return profileName;
        }
        return levelName;
    }

    private static @Nullable Integer getDolbyVisionProfileNumber(int profile) {
        switch (profile) {
            case CodecProfileLevel.DolbyVisionProfileDvavPer:
                return 0;
            case CodecProfileLevel.DolbyVisionProfileDvavPen:
                return 1;
            case CodecProfileLevel.DolbyVisionProfileDvheDer:
                return 2;
            case CodecProfileLevel.DolbyVisionProfileDvheDen:
                return 3;
            case CodecProfileLevel.DolbyVisionProfileDvheDtr:
                return 4;
            case CodecProfileLevel.DolbyVisionProfileDvheStn:
                return 5;
            case CodecProfileLevel.DolbyVisionProfileDvheDth:
                return 6;
            case CodecProfileLevel.DolbyVisionProfileDvheDtb:
                return 7;
            case CodecProfileLevel.DolbyVisionProfileDvheSt:
                return 8;
            case CodecProfileLevel.DolbyVisionProfileDvavSe:
                return 9;
            default:
                return null;
        }
    }

    public static String getProfileName(String mimeType, int profile) {
        if (mimeType == null) return "Unknown";

        switch (mimeType) {
            case MimeTypes.VIDEO_H264: // video/avc
                switch (profile) {
                    case CodecProfileLevel.AVCProfileBaseline: return "Baseline";
                    case CodecProfileLevel.AVCProfileConstrainedBaseline: return "Constrained Baseline";
                    case CodecProfileLevel.AVCProfileMain: return "Main";
                    case CodecProfileLevel.AVCProfileExtended: return "Extended";
                    case CodecProfileLevel.AVCProfileHigh: return "High";
                    case CodecProfileLevel.AVCProfileHigh10: return "High 10";
                    case CodecProfileLevel.AVCProfileHigh422: return "High 4:2:2";
                    case CodecProfileLevel.AVCProfileHigh444: return "High 4:4:4";
                    case CodecProfileLevel.AVCProfileConstrainedHigh: return "Constrained High";
                }
                break;
            case MimeTypes.VIDEO_H265: // video/hevc
                switch (profile) {
                    case CodecProfileLevel.HEVCProfileMain: return "Main";
                    case CodecProfileLevel.HEVCProfileMain10: return "Main 10";
                    case CodecProfileLevel.HEVCProfileMainStill: return "Main Still";
                    case CodecProfileLevel.HEVCProfileMain10HDR10: return "Main 10 HDR10";
                    case CodecProfileLevel.HEVCProfileMain10HDR10Plus: return "Main 10 HDR10+";
                    // Dolby Vision profiles sometimes showing up as HEVC if rewritten or compatible
                    // But usually, they are mapped to Dolby constants if the mime is Dolby
                }
                break;
            case MimeTypes.VIDEO_DOLBY_VISION: // video/dolby-vision
                switch (profile) {
                    case CodecProfileLevel.DolbyVisionProfileDvavPer: return "Profile 0 (DvavPer)";
                    case CodecProfileLevel.DolbyVisionProfileDvavPen: return "Profile 1 (DvavPen)";
                    case CodecProfileLevel.DolbyVisionProfileDvheDer: return "Profile 2 (DvheDer)";
                    case CodecProfileLevel.DolbyVisionProfileDvheDen: return "Profile 3 (DvheDen)";
                    case CodecProfileLevel.DolbyVisionProfileDvheDtr: return "Profile 4 (DvheDtr)";
                    case CodecProfileLevel.DolbyVisionProfileDvheStn: return "Profile 5 (DvheStn)";
                    case CodecProfileLevel.DolbyVisionProfileDvheDth: return "Profile 6 (DvheDth)";
                    // case CodecProfileLevel.DolbyVisionProfileDvheDtv: return "Profile 7 (DvheDtv)"; // Not available in current Media3 version
                    case CodecProfileLevel.DolbyVisionProfileDvheSt: return "Profile 8 (DvheSt)";
                    case CodecProfileLevel.DolbyVisionProfileDvavSe: return "Profile 9 (DvavSe)";
                }
                break;
            case MimeTypes.VIDEO_VP9: // video/x-vnd.on2.vp9
                 switch (profile) {
                     case CodecProfileLevel.VP9Profile0: return "Profile 0";
                     case CodecProfileLevel.VP9Profile1: return "Profile 1";
                     case CodecProfileLevel.VP9Profile2: return "Profile 2";
                     case CodecProfileLevel.VP9Profile2HDR: return "Profile 2 HDR";
                     case CodecProfileLevel.VP9Profile2HDR10Plus: return "Profile 2 HDR10+";
                     case CodecProfileLevel.VP9Profile3: return "Profile 3";
                     case CodecProfileLevel.VP9Profile3HDR: return "Profile 3 HDR";
                     case CodecProfileLevel.VP9Profile3HDR10Plus: return "Profile 3 HDR10+";
                 }
                 break;
             case MimeTypes.VIDEO_AV1: // video/av01
                 switch (profile) {
                     case CodecProfileLevel.AV1ProfileMain8: return "Main 8";
                     case CodecProfileLevel.AV1ProfileMain10: return "Main 10";
                     case CodecProfileLevel.AV1ProfileMain10HDR10: return "Main 10 HDR10";
                     case CodecProfileLevel.AV1ProfileMain10HDR10Plus: return "Main 10 HDR10+";
                 }
                 break;
        }
        return "Unknown (" + profile + ")";
    }

    public static String getLevelName(String mimeType, int level) {
         if (mimeType == null) return "Unknown";
         
         // Implementations for levels 
         // For brevity, handling common ones. This can be expanded.
         switch (mimeType) {
            case MimeTypes.VIDEO_H264:
                switch (level) {
                   case CodecProfileLevel.AVCLevel1: return "1";
                   case CodecProfileLevel.AVCLevel1b: return "1b";
                   case CodecProfileLevel.AVCLevel11: return "1.1";
                   case CodecProfileLevel.AVCLevel12: return "1.2";
                   case CodecProfileLevel.AVCLevel13: return "1.3";
                   case CodecProfileLevel.AVCLevel2: return "2";
                   case CodecProfileLevel.AVCLevel21: return "2.1";
                   case CodecProfileLevel.AVCLevel22: return "2.2";
                   case CodecProfileLevel.AVCLevel3: return "3";
                   case CodecProfileLevel.AVCLevel31: return "3.1";
                   case CodecProfileLevel.AVCLevel32: return "3.2";
                   case CodecProfileLevel.AVCLevel4: return "4";
                   case CodecProfileLevel.AVCLevel41: return "4.1";
                   case CodecProfileLevel.AVCLevel42: return "4.2";
                   case CodecProfileLevel.AVCLevel5: return "5";
                   case CodecProfileLevel.AVCLevel51: return "5.1";
                   case CodecProfileLevel.AVCLevel52: return "5.2";
                   case CodecProfileLevel.AVCLevel6: return "6";
                   case CodecProfileLevel.AVCLevel61: return "6.1";
                   case CodecProfileLevel.AVCLevel62: return "6.2";
                }
                break;
            case MimeTypes.VIDEO_H265:
                switch (level) {
                    case CodecProfileLevel.HEVCMainTierLevel1: return "Main Tier 1";
                    case CodecProfileLevel.HEVCHighTierLevel1: return "High Tier 1";
                    case CodecProfileLevel.HEVCMainTierLevel2: return "Main Tier 2";
                    case CodecProfileLevel.HEVCHighTierLevel2: return "High Tier 2";
                    case CodecProfileLevel.HEVCMainTierLevel21: return "Main Tier 2.1";
                    case CodecProfileLevel.HEVCHighTierLevel21: return "High Tier 2.1";
                    case CodecProfileLevel.HEVCMainTierLevel3: return "Main Tier 3";
                    case CodecProfileLevel.HEVCHighTierLevel3: return "High Tier 3";
                    case CodecProfileLevel.HEVCMainTierLevel31: return "Main Tier 3.1";
                    case CodecProfileLevel.HEVCHighTierLevel31: return "High Tier 3.1";
                    case CodecProfileLevel.HEVCMainTierLevel4: return "Main Tier 4";
                    case CodecProfileLevel.HEVCHighTierLevel4: return "High Tier 4";
                    case CodecProfileLevel.HEVCMainTierLevel41: return "Main Tier 4.1";
                    case CodecProfileLevel.HEVCHighTierLevel41: return "High Tier 4.1";
                    case CodecProfileLevel.HEVCMainTierLevel5: return "Main Tier 5";
                    case CodecProfileLevel.HEVCHighTierLevel5: return "High Tier 5";
                    case CodecProfileLevel.HEVCMainTierLevel51: return "Main Tier 5.1";
                    case CodecProfileLevel.HEVCHighTierLevel51: return "High Tier 5.1";
                    case CodecProfileLevel.HEVCMainTierLevel52: return "Main Tier 5.2";
                    case CodecProfileLevel.HEVCHighTierLevel52: return "High Tier 5.2";
                    case CodecProfileLevel.HEVCMainTierLevel6: return "Main Tier 6";
                    case CodecProfileLevel.HEVCHighTierLevel6: return "High Tier 6";
                    case CodecProfileLevel.HEVCMainTierLevel61: return "Main Tier 6.1";
                    case CodecProfileLevel.HEVCHighTierLevel61: return "High Tier 6.1";
                    case CodecProfileLevel.HEVCMainTierLevel62: return "Main Tier 6.2";
                    case CodecProfileLevel.HEVCHighTierLevel62: return "High Tier 6.2";
                }
                break;
            case MimeTypes.VIDEO_DOLBY_VISION:
                // Dolby Vision levels are interesting, they are usually just matching HEVC levels or specific DV ones.
                // The CodecProfileLevel class has constants for them.
                switch (level) {
                    case CodecProfileLevel.DolbyVisionLevelHd24: return "HD 24";
                    case CodecProfileLevel.DolbyVisionLevelHd30: return "HD 30";
                    case CodecProfileLevel.DolbyVisionLevelFhd24: return "FHD 24";
                    case CodecProfileLevel.DolbyVisionLevelFhd30: return "FHD 30";
                    case CodecProfileLevel.DolbyVisionLevelFhd60: return "FHD 60";
                    case CodecProfileLevel.DolbyVisionLevelUhd24: return "UHD 24";
                    case CodecProfileLevel.DolbyVisionLevelUhd30: return "UHD 30";
                    case CodecProfileLevel.DolbyVisionLevelUhd48: return "UHD 48";
                    case CodecProfileLevel.DolbyVisionLevelUhd60: return "UHD 60";
                    case CodecProfileLevel.DolbyVisionLevelUhd120: return "UHD 120";
                    case CodecProfileLevel.DolbyVisionLevel8k30: return "8k 30";
                    case CodecProfileLevel.DolbyVisionLevel8k60: return "8k 60";
                }
                break;
         }
         return "Unknown (" + level + ")";
    }
}
