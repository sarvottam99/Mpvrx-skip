package mkvbridge

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"sort"
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

	return os.NewFile(uintptr(dup), path), nil
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
	case "first":
		lengthMs := int64(req.Minutes)*60000 + int64(req.Seconds)*1000
		if lengthMs <= 0 {
			return 0, 0, fmt.Errorf("chapter duration must be greater than zero")
		}

		startMs = 0
		endMs = lengthMs

	case "last":
		lengthMs := int64(req.Minutes)*60000 + int64(req.Seconds)*1000
		if lengthMs <= 0 {
			return 0, 0, fmt.Errorf("chapter duration must be greater than zero")
		}

		if lengthMs > durationMs {
			lengthMs = durationMs
		}

		startMs = durationMs - lengthMs
		endMs = durationMs

	case "range":
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

func EditChapters(fd int64, requestJSON string) error {
	var req chapterRequest
	if err := json.Unmarshal([]byte(requestJSON), &req); err != nil {
		return fmt.Errorf("invalid chapter request: %w", err)
	}

	if req.Title == "" {
		return fmt.Errorf("chapter name cannot be empty")
	}

	var timingErr error

	err := matroska.EditInPlace(
		context.Background(),
		"android-document",
		func(c *matroska.Container) {
			var startMs, endMs int64

			startMs, endMs, timingErr = chapterTiming(c.DurationMs, req)
			if timingErr != nil {
				return
			}

			newChapter := matroska.Chapter{
				Title:   req.Title,
				StartMs: startMs,
				EndMs:   endMs,
			}

			updated := make([]matroska.Chapter, 0, len(c.Chapters)+1)
			replaced := false

			for _, chapter := range c.Chapters {
				if chapter.Title == req.Title &&
					chapter.StartMs == startMs &&
					chapter.EndMs == endMs {
					updated = append(updated, newChapter)
					replaced = true
				} else {
					updated = append(updated, chapter)
				}
			}

			if !replaced {
				updated = append(updated, newChapter)
			}

			sort.SliceStable(updated, func(i, j int) bool {
				return updated[i].StartMs < updated[j].StartMs
			})

			c.Chapters = updated
		},
		matroska.Options{
			FS: androidFS(fd),
		},
	)

	if err != nil {
		return err
	}

	if timingErr != nil {
		return timingErr
	}

	return nil
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

func EditSubtitleTracks(fd int64, requestJSON string) error {
	var req subtitleEditRequest
	if err := json.Unmarshal([]byte(requestJSON), &req); err != nil {
		return fmt.Errorf("invalid subtitle edit request: %w", err)
	}

	if req.SubtitleIndex < 1 {
		return fmt.Errorf("subtitle index must be at least 1")
	}

	if req.NewPosition < 0 {
		return fmt.Errorf("subtitle position cannot be negative")
	}

	return matroska.EditInPlace(
		context.Background(),
		"android-document",
		func(c *matroska.Container) {
			subtitlePositions := make([]int, 0)

			for i := range c.Tracks {
				if c.Tracks[i].Type == matroska.SubtitleTrack {
					subtitlePositions = append(subtitlePositions, i)
				}
			}

			if req.SubtitleIndex > len(subtitlePositions) {
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

			if req.NewPosition < 1 ||
				req.NewPosition > len(subtitlePositions) ||
				req.NewPosition == req.SubtitleIndex {
				return
			}

			subtitleTracks := make([]matroska.Track, 0, len(subtitlePositions))

			for _, pos := range subtitlePositions {
				subtitleTracks = append(subtitleTracks, c.Tracks[pos])
			}

			moved := subtitleTracks[req.SubtitleIndex-1]

			remaining := make([]matroska.Track, 0, len(subtitleTracks)-1)

			for i, track := range subtitleTracks {
				if i != req.SubtitleIndex-1 {
					remaining = append(remaining, track)
				}
			}

			insertAt := req.NewPosition - 1

			reordered := make([]matroska.Track, 0, len(subtitleTracks))
			remainingIndex := 0

			for i := 0; i < len(subtitleTracks); i++ {
				if i == insertAt {
					reordered = append(reordered, moved)
				} else {
					reordered = append(reordered, remaining[remainingIndex])
					remainingIndex++
				}
			}

			subtitleIndex := 0

			for i := range c.Tracks {
				if c.Tracks[i].Type == matroska.SubtitleTrack {
					c.Tracks[i] = reordered[subtitleIndex]
					subtitleIndex++
				}
			}
		},
		matroska.Options{
			FS: androidFS(fd),
		},
	)
}
