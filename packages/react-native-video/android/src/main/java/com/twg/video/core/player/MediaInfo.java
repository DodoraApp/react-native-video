package com.twg.video.core.player;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Container for extracted media information including video properties and chapters.
 *
 * <p>Ported from the DodoStream fork (v6) — MediaInfo + MediaInfoLoader were originally
 * implemented by the fork on top of nextlib's FFmpeg-based media analysis.
 */
public class MediaInfo {

  /** Represents the type of a chapter based on its content. */
  public enum ChapterType {
    RECAP(1, "RECAP"),
    PREVIEW(2, "PREVIEW"),
    INTRO(3, "INTRO"),
    CREDITS(4, "CREDITS"),
    UNKNOWN(5, "UNKNOWN");

    private final int value;
    private final String name;

    ChapterType(int value, String name) {
      this.value = value;
      this.name = name;
    }

    public int getValue() {
      return value;
    }

    public String getName() {
      return name;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  // Regex patterns for chapter type detection
  private static final Pattern RECAP_REGEX = Pattern.compile("recap|previously", Pattern.CASE_INSENSITIVE);
  private static final Pattern PREVIEW_REGEX = Pattern.compile("preview", Pattern.CASE_INSENSITIVE);
  private static final Pattern INTRO_REGEX = Pattern.compile("intro|opening|^op$|title sequence", Pattern.CASE_INSENSITIVE);
  private static final Pattern CREDITS_REGEX = Pattern.compile("outro|credits|ending|^ed$", Pattern.CASE_INSENSITIVE);
  // Safeguards for chapter type detection
  private static final long MAX_INTRO_DURATION_MS = 5 * 60 * 1000; // 5 minutes max for intro
  private static final long MAX_CREDITS_DURATION_MS = 10 * 60 * 1000; // 10 minutes max for credits
  private static final long MAX_RECAP_DURATION_MS = 5 * 60 * 1000; // 5 minutes max for recap
  private static final long MAX_PREVIEW_DURATION_MS = 3 * 60 * 1000; // 3 minutes max for preview
  private static final double MIN_POSITION_FOR_CREDITS_RATIO = 0.7; // Credits should be in last 30% of video
  private static final double MAX_POSITION_FOR_INTRO_RATIO = 0.3; // Intro should be in first 30% of video
  private static final double MAX_POSITION_FOR_RECAP_RATIO = 0.2; // Recap should be in first 20% of video

  /**
   * Parses a chapter type from its title and position in the video.
   * Applies safeguards to ensure reasonable chapter categorization.
   *
   * @param title The chapter title
   * @param startMs Chapter start time in milliseconds
   * @param endMs Chapter end time in milliseconds
   * @param durationMs Total video duration in milliseconds
   * @return The parsed ChapterType
   */
  public static ChapterType parseChapterType(String title, long startMs, long endMs, long durationMs) {
    if (title == null || title.isEmpty()) {
      return ChapterType.UNKNOWN;
    }

    long chapterDuration = endMs - startMs;
    double startRatio = durationMs > 0 ? (double) startMs / durationMs : 0;
    double endRatio = durationMs > 0 ? (double) endMs / durationMs : 0;

    // Check for RECAP - typically at the beginning
    if (RECAP_REGEX.matcher(title).find()) {
      // Safeguard: recap should be in first 20% and not too long
      if (startRatio <= MAX_POSITION_FOR_RECAP_RATIO && chapterDuration <= MAX_RECAP_DURATION_MS) {
        return ChapterType.RECAP;
      }
    }

    // Check for PREVIEW - typically early in the video
    if (PREVIEW_REGEX.matcher(title).find()) {
      // Safeguard: preview should not be too long
      if (chapterDuration <= MAX_PREVIEW_DURATION_MS) {
        return ChapterType.PREVIEW;
      }
    }

    // Check for INTRO - typically at the beginning
    if (INTRO_REGEX.matcher(title).find()) {
      // Safeguards: intro should be in first 30%, not at the very end, and not too long
      if (startRatio <= MAX_POSITION_FOR_INTRO_RATIO
          && endRatio < 0.95 // Not ending near the end of video
          && chapterDuration <= MAX_INTRO_DURATION_MS) {
        return ChapterType.INTRO;
      }
    }

    // Check for CREDITS - typically at the end
    if (CREDITS_REGEX.matcher(title).find()) {
      // Safeguard: credits should be in last 30% and not too long
      if (startRatio >= MIN_POSITION_FOR_CREDITS_RATIO && chapterDuration <= MAX_CREDITS_DURATION_MS) {
        return ChapterType.CREDITS;
      }
    }

    return ChapterType.UNKNOWN;
  }

  private final long durationMs;
  private final long videoWidth;
  private final long videoHeight;
  private final float frameRate;
  private final long bitrate;
  private final List<Chapter> chapters;

  public MediaInfo(long durationMs, long videoWidth, long videoHeight, float frameRate, long bitrate, List<Chapter> chapters) {
    this.durationMs = durationMs;
    this.videoWidth = videoWidth;
    this.videoHeight = videoHeight;
    this.frameRate = frameRate;
    this.bitrate = bitrate;
    this.chapters = chapters != null ? chapters : new ArrayList<>();
  }

  public long getDurationMs() {
    return durationMs;
  }

  public long getVideoWidth() {
    return videoWidth;
  }

  public long getVideoHeight() {
    return videoHeight;
  }

  public float getFrameRate() {
    return frameRate;
  }

  public long getBitrate() {
    return bitrate;
  }

  public List<Chapter> getChapters() {
    return chapters;
  }

  /** Represents a chapter in the media. */
  public static class Chapter {
    private final String title;
    private final long startMs;
    private final long endMs;
    private final ChapterType type;

    public Chapter(String title, long startMs, long endMs, ChapterType type) {
      this.title = title != null ? title : "";
      this.startMs = startMs;
      this.endMs = endMs;
      this.type = type != null ? type : ChapterType.UNKNOWN;
    }

    public String getTitle() {
      return title;
    }

    public long getStartMs() {
      return startMs;
    }

    public long getEndMs() {
      return endMs;
    }

    public ChapterType getType() {
      return type;
    }

    @Override
    public String toString() {
      return "Chapter{"
          + "title='"
          + title
          + '\''
          + ", startMs="
          + startMs
          + ", endMs="
          + endMs
          + ", type="
          + type
          + '}';
    }
  }

  @Override
  public String toString() {
    return "MediaInfo{"
        + "durationMs="
        + durationMs
        + ", videoWidth="
        + videoWidth
        + ", videoHeight="
        + videoHeight
        + ", frameRate="
        + frameRate
        + ", bitrate="
        + bitrate
        + ", chapters="
        + chapters.size()
        + '}';
  }
}
