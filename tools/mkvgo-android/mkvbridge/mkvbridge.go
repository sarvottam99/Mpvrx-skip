package mkvbridge

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"sort"
	"strings"
	"syscall"

	"github.com/gravity-zero/mkvgo/matroska"
	"github.com/gravity-zero/mkvgo/mkv"
)

type chapterRequest struct {
	Title        string `json:"title"`
	Mode         string `json:"mode"`
	Minutes      int    `json:"minutes"`
	Seconds      int    `json:"seconds"`
	StartMinutes int    `json:"start_minutes"`
	StartSeconds int    `json:"start_seconds"`
	EndMinutes   int    `json:"end_minutes"`
	EndSeconds   int    `json:"end_seconds"`
}

type subtitleEditRequest struct {
	SubtitleIndex int    `json:"subtitle_index"`
	Name          string `json:"name"`
	ChangeName    bool   `json:"change_name"`
	DefaultMode   string `json:"default_mode"`
	NewPosition   int    `json:"new_position"`
}

type subtitleTrackInfo struct {
	Index     int    `json:"index"`
	ID        uint64 `json:"id"`
	Name      string `json:"name"`
	Language  string `json:"language"`
	Codec     string `json:"codec"`
	IsDefault bool   `json:"is_default"`
	IsForced  bool   `json:"is_forced"`
}

func dupFile(fd int64, path string) (*os.File, error) {
	if fd < 0 || fd > int64(^uint(0)>>1) {
		return nil, fmt.Errorf("invalid file descriptor: %d", fd)
	}

	dup, err := syscall.Dup(int(fd))
	if err != nil {
		return nil, fmt.Errorf("duplicate file descriptor: %w", err)
	}

	file := os.NewFile(uintptr(dup), path)
	if file == nil {
		syscall.Close(dup)
		return nil, fmt.Errorf("create file from duplicated descriptor")
	}

	if _, err := file.Seek(0, io.SeekStart); err != nil {
		file.Close()
		return nil, fmt.Errorf("seek duplicated file to start: %w", err)
	}

	return file, nil
}

func androidFS(fd int64) *matroska.FS {
	const path = "android-document"

	return &matroska.FS{
		Open: func(_ string) (mkv.ReadSeekCloser, error) {
			return dupFile(fd, path)
		},
		OpenFile: func(_ string, _ int, _ os.FileMode) (mkv.ReadWriteSeekCloser, error) {
			return dupFile(fd, path)
		},
		Stat: func(_ string) (os.FileInfo, error) {
			f, err := dupFile(fd, path)
			if err != nil {
				return nil, err
			}
			defer f.Close()
			return f.Stat()
		},
	}
}

func chapterTiming(durationMs int64, req chapterRequest) (int64, int64, error) {
	if durationMs <= 0 {
		return 0, 0, fmt.Errorf("video duration could not be determined")
	}

	var startMs, endMs int64

	switch req.Mode {
	case "first", "last":
		if req.Minutes < 0 {
			return 0, 0, fmt.Errorf("chapter minutes cannot be negative")
		}
		if req.Seconds < 0 || req.Seconds > 59 {
			return 0, 0, fmt.Errorf("chapter seconds must be between 0 and 59")
		}

		lengthMs := int64(req.Minutes)*60000 + int64(req.Seconds)*1000
		if lengthMs <= 0 {
			return 0, 0, fmt.Errorf("chapter duration must be greater than zero")
		}

		if req.Mode == "first" {
			startMs = 0
			endMs = lengthMs
		} else {
			if lengthMs > durationMs {
				lengthMs = durationMs
			}
			startMs = durationMs - lengthMs
			endMs = durationMs
		}

	case "range":
		if req.StartMinutes < 0 || req.EndMinutes < 0 {
			return 0, 0, fmt.Errorf("chapter minutes cannot be negative")
		}
		if req.StartSeconds < 0 || req.StartSeconds > 59 ||
			req.EndSeconds < 0 || req.EndSeconds > 59 {
			return 0, 0, fmt.Errorf("chapter seconds must be between 0 and 59")
		}
		startMs = int64(req.StartMinutes)*60000 + int64(req.StartSeconds)*1000
		endMs = int64(req.EndMinutes)*60000 + int64(req.EndSeconds)*1000

	default:
		return 0, 0, fmt.Errorf("invalid chapter timing mode: %s", req.Mode)
	}

	if startMs < 0 {
		startMs = 0
	}
	if endMs > durationMs {
		endMs = durationMs
	}
	if endMs <= startMs {
		return 0, 0, fmt.Errorf("chapter end time must be after start time")
	}

	return startMs, endMs, nil
}

func needsFullRewrite(err error) bool {
	if err == nil {
		return false
	}

	message := strings.ToLower(err.Error())
	return strings.Contains(message, "use full rewrite instead") ||
		strings.Contains(message, "does not fit existing region") ||
		strings.Contains(message, "which no ebml element can fill")
}

func copyAndroidFileToTemp(fd int64, path string) error {
	src, err := dupFile(fd, "android-source")
	if err != nil {
		return err
	}
	defer src.Close()

	if _, err := src.Seek(0, io.SeekStart); err != nil {
		return fmt.Errorf("seek source: %w", err)
	}

	dst, err := os.Create(path)
	if err != nil {
		return fmt.Errorf("create temporary source: %w", err)
	}

	if _, err := io.Copy(dst, src); err != nil {
		dst.Close()
		return fmt.Errorf("copy source to temporary file: %w", err)
	}

	if err := dst.Close(); err != nil {
		return fmt.Errorf("close temporary source: %w", err)
	}

	return nil
}

func restoreAndroidFile(fd int64, backupPath string) error {
	src, err := os.Open(backupPath)
	if err != nil {
		return fmt.Errorf("open original-file backup: %w", err)
	}
	defer src.Close()

	dst, err := dupFile(fd, "android-restore")
	if err != nil {
		return err
	}
	defer dst.Close()

	if _, err := src.Seek(0, io.SeekStart); err != nil {
		return fmt.Errorf("seek original-file backup: %w", err)
	}
	if err := dst.Truncate(0); err != nil {
		return fmt.Errorf("truncate destination during restore: %w", err)
	}
	if _, err := dst.Seek(0, io.SeekStart); err != nil {
		return fmt.Errorf("seek destination during restore: %w", err)
	}
	if _, err := io.Copy(dst, src); err != nil {
		return fmt.Errorf("restore original file contents: %w", err)
	}
	if err := dst.Sync(); err != nil {
		return fmt.Errorf("sync restored original file: %w", err)
	}
	return nil
}

func restoreAfterCopyFailure(fd int64, backupPath string, cause error) error {
	if restoreErr := restoreAndroidFile(fd, backupPath); restoreErr != nil {
		return fmt.Errorf("%v; restoring the original file also failed: %w", cause, restoreErr)
	}
	return cause
}

func copyTempFileToAndroid(fd int64, path string, backupPath string) error {
	src, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("open rewritten file: %w", err)
	}
	defer src.Close()

	dst, err := dupFile(fd, "android-destination")
	if err != nil {
		return err
	}
	defer dst.Close()

	if _, err := src.Seek(0, io.SeekStart); err != nil {
		return fmt.Errorf("seek rewritten file: %w", err)
	}
	if _, err := dst.Seek(0, io.SeekStart); err != nil {
		return fmt.Errorf("seek destination: %w", err)
	}
	if err := dst.Truncate(0); err != nil {
		return restoreAfterCopyFailure(fd, backupPath, fmt.Errorf("truncate destination: %w", err))
	}
	if _, err := dst.Seek(0, io.SeekStart); err != nil {
		return restoreAfterCopyFailure(fd, backupPath, fmt.Errorf("seek destination after truncation: %w", err))
	}
	if _, err := io.Copy(dst, src); err != nil {
		return restoreAfterCopyFailure(fd, backupPath, fmt.Errorf("copy rewritten file to destination: %w", err))
	}
	if err := dst.Sync(); err != nil {
		return restoreAfterCopyFailure(fd, backupPath, fmt.Errorf("sync rewritten destination: %w", err))
	}
	return nil
}

func fullRewrite(
	fd int64,
	tempDir string,
	edit func(*matroska.Container),
) error {
	if tempDir == "" {
		return fmt.Errorf("temporary directory is empty")
	}

	if err := os.MkdirAll(tempDir, 0700); err != nil {
		return fmt.Errorf("create temporary directory: %w", err)
	}

	sourceFile, err := os.CreateTemp(tempDir, "mkvbridge-source-*.mkv")
	if err != nil {
		return fmt.Errorf("create temporary source: %w", err)
	}
	sourcePath := sourceFile.Name()
	sourceFile.Close()
	defer os.Remove(sourcePath)

	outputFile, err := os.CreateTemp(tempDir, "mkvbridge-output-*.mkv")
	if err != nil {
		return fmt.Errorf("create temporary output: %w", err)
	}
	outputPath := outputFile.Name()
	outputFile.Close()
	defer os.Remove(outputPath)

	if err := copyAndroidFileToTemp(fd, sourcePath); err != nil {
		return err
	}

	if err := matroska.EditMetadata(
		context.Background(),
		sourcePath,
		outputPath,
		edit,
	); err != nil {
		return fmt.Errorf("full metadata rewrite: %w", err)
	}

	if err := copyTempFileToAndroid(fd, outputPath, sourcePath); err != nil {
		return err
	}

	return nil
}

func editMetadata(
	fd int64,
	tempDir string,
	edit func(*matroska.Container),
) error {
	err := matroska.EditInPlace(
		context.Background(),
		"android-document",
		edit,
		matroska.Options{
			FS: androidFS(fd),
		},
	)

	if err == nil {
		return nil
	}

	if !needsFullRewrite(err) {
		return err
	}

	return fullRewrite(fd, tempDir, edit)
}

func upsertChapter(chapters []matroska.Chapter, newChapter matroska.Chapter) []matroska.Chapter {
	updated := make([]matroska.Chapter, 0, len(chapters)+1)
	replaced := false

	for _, chapter := range chapters {
		if chapter.StartMs == newChapter.StartMs {
			if !replaced {
				updated = append(updated, newChapter)
				replaced = true
			}
			continue
		}

		updated = append(updated, chapter)
	}

	if !replaced {
		updated = append(updated, newChapter)
	}

	sort.SliceStable(updated, func(i, j int) bool {
		return updated[i].StartMs < updated[j].StartMs
	})

	return updated
}

func EditChapters(fd int64, tempDir string, requestJSON string) error {
	var req chapterRequest
	if err := json.Unmarshal([]byte(requestJSON), &req); err != nil {
		return fmt.Errorf("invalid chapter request: %w", err)
	}

	req.Title = strings.TrimSpace(req.Title)
	if req.Title == "" {
		return fmt.Errorf("chapter name cannot be empty")
	}

	// Validate the timing before calling EditInPlace. The library's edit callback
	// cannot return an error, so validating inside it would still rewrite the
	// original metadata even when the requested range is invalid.
	probe, err := matroska.OpenMetaWithFS(
		context.Background(),
		"android-document",
		androidFS(fd),
	)
	if err != nil {
		return fmt.Errorf("inspect video metadata: %w", err)
	}
	startMs, endMs, err := chapterTiming(probe.DurationMs, req)
	if err != nil {
		return err
	}

	return editMetadata(fd, tempDir, func(c *matroska.Container) {
		newChapter := matroska.Chapter{
			Title:   req.Title,
			StartMs: startMs,
			EndMs:   endMs,
		}

		c.Chapters = upsertChapter(c.Chapters, newChapter)
	})
}

func subtitleTrackPositions(c *matroska.Container) []int {
	positions := make([]int, 0)
	for i := range c.Tracks {
		if c.Tracks[i].Type == matroska.SubtitleTrack {
			positions = append(positions, i)
		}
	}
	return positions
}

func InspectSubtitleTracks(fd int64) (string, error) {
	var result []subtitleTrackInfo

	c, err := matroska.OpenMetaWithFS(
		context.Background(),
		"android-document",
		androidFS(fd),
	)
	if err != nil {
		return "", err
	}

	subtitleIndex := 0

	for _, track := range c.Tracks {
		if track.Type != matroska.SubtitleTrack {
			continue
		}

		subtitleIndex++

		result = append(result, subtitleTrackInfo{
			Index:     subtitleIndex,
			ID:        track.ID,
			Name:      track.Name,
			Language:  track.ResolvedLanguage(),
			Codec:     track.Codec,
			IsDefault: track.IsDefault,
			IsForced:  track.IsForced,
		})
	}

	data, err := json.Marshal(result)
	if err != nil {
		return "", err
	}

	return string(data), nil
}

func EditSubtitleTracks(fd int64, tempDir string, requestJSON string) error {
	var req subtitleEditRequest
	if err := json.Unmarshal([]byte(requestJSON), &req); err != nil {
		return fmt.Errorf("invalid subtitle edit request: %w", err)
	}
	if req.SubtitleIndex < 1 {
		return fmt.Errorf("subtitle index must be at least 1")
	}
	if req.NewPosition < 1 {
		return fmt.Errorf("subtitle position must be at least 1")
	}
	switch req.DefaultMode {
	case "", "keep", "default", "not_default":
	default:
		return fmt.Errorf("invalid subtitle default mode: %s", req.DefaultMode)
	}

	// Check both positions before editing anything. Files in the same folder
	// may not all contain the same number of subtitle tracks.
	probe, err := matroska.OpenMetaWithFS(
		context.Background(),
		"android-document",
		androidFS(fd),
	)
	if err != nil {
		return fmt.Errorf("inspect subtitle tracks: %w", err)
	}
	probePositions := subtitleTrackPositions(probe)
	if req.SubtitleIndex > len(probePositions) {
		return fmt.Errorf(
			"subtitle track %d does not exist; file has %d subtitle tracks",
			req.SubtitleIndex,
			len(probePositions),
		)
	}
	if req.NewPosition > len(probePositions) {
		return fmt.Errorf(
			"subtitle position %d is out of range; file has %d subtitle tracks",
			req.NewPosition,
			len(probePositions),
		)
	}

	var editErr error
	err = editMetadata(fd, tempDir, func(c *matroska.Container) {
		subtitlePositions := subtitleTrackPositions(c)
		if req.SubtitleIndex > len(subtitlePositions) ||
			req.NewPosition > len(subtitlePositions) {
			editErr = fmt.Errorf("subtitle track layout changed while editing")
			return
		}

		target := subtitlePositions[req.SubtitleIndex-1]
		if req.ChangeName {
			c.Tracks[target].Name = req.Name
		}

		switch req.DefaultMode {
		case "default":
			for _, pos := range subtitlePositions {
				c.Tracks[pos].IsDefault = false
			}
			c.Tracks[target].IsDefault = true
		case "not_default":
			c.Tracks[target].IsDefault = false
		}

		if req.NewPosition == req.SubtitleIndex {
			return
		}

		subtitleTracks := make([]matroska.Track, 0, len(subtitlePositions))
		for _, pos := range subtitlePositions {
			subtitleTracks = append(subtitleTracks, c.Tracks[pos])
		}

		from := req.SubtitleIndex - 1
		to := req.NewPosition - 1
		moved := subtitleTracks[from]
		subtitleTracks = append(subtitleTracks[:from], subtitleTracks[from+1:]...)
		subtitleTracks = append(subtitleTracks, matroska.Track{})
		copy(subtitleTracks[to+1:], subtitleTracks[to:])
		subtitleTracks[to] = moved

		subtitleIndex := 0
		for i := range c.Tracks {
			if c.Tracks[i].Type == matroska.SubtitleTrack {
				c.Tracks[i] = subtitleTracks[subtitleIndex]
				subtitleIndex++
			}
		}
	})
	if err != nil {
		return err
	}
	if editErr != nil {
		return editErr
	}
	return nil
}
