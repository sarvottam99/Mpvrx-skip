package mpvrxmkvgo

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/gravity-zero/mkvgo/matroska"
)

type Track struct {
	Index      int
	ID         uint64
	Type       string
	Name       string
	Language   string
	Default    bool
	Forced     bool
}

type Chapter struct {
	Title   string
	StartMs int64
	EndMs   int64
}

type FileInfo struct {
	DurationMs int64
	Tracks     []Track
	Chapters   []Chapter
}

func Probe(path string) (string, error) {
	ctx := context.Background()

	c, err := matroska.OpenMeta(ctx, path)
	if err != nil {
		return "", err
	}

	type result struct {
		DurationMs int64     `json:"durationMs"`
		Tracks     []Track   `json:"tracks"`
		Chapters   []Chapter `json:"chapters"`
	}

	out := result{
		DurationMs: c.Info.DurationMs,
	}

	for i, t := range c.Tracks {
		out.Tracks = append(out.Tracks, Track{
			Index:    i,
			ID:       t.ID,
			Type:     fmt.Sprint(t.Type),
			Name:     t.Name,
			Language: t.Language,
			Default:  t.IsDefault,
			Forced:   t.IsForced,
		})
	}

	for _, ch := range c.Chapters {
		out.Chapters = append(out.Chapters, Chapter{
			Title:   ch.Title,
			StartMs: ch.StartMs,
			EndMs:   ch.EndMs,
		})
	}

	data, err := json.Marshal(out)
	if err != nil {
		return "", err
	}

	return string(data), nil
}

func Edit(
	srcPath string,
	dstPath string,
	trackOrderJson string,
	chaptersJson string,
) error {
	ctx := context.Background()

	var order []int
	if trackOrderJson != "" {
		if err := json.Unmarshal([]byte(trackOrderJson), &order); err != nil {
			return err
		}
	}

	var chapters []Chapter
	if chaptersJson != "" {
		if err := json.Unmarshal([]byte(chaptersJson), &chapters); err != nil {
			return err
		}
	}

	return matroska.EditMetadata(
		ctx,
		srcPath,
		dstPath,
		func(c *matroska.Container) {
			if len(order) > 0 {
				reordered := make([]matroska.Track, 0, len(c.Tracks))

				for _, index := range order {
					if index >= 0 && index < len(c.Tracks) {
						reordered = append(reordered, c.Tracks[index])
					}
				}

				if len(reordered) == len(c.Tracks) {
					c.Tracks = reordered
				}
			}

			if chaptersJson != "" {
				c.Chapters = nil

				for _, chapter := range chapters {
					c.Chapters = append(
						c.Chapters,
						matroska.Chapter{
							Title:   chapter.Title,
							StartMs: chapter.StartMs,
							EndMs:   chapter.EndMs,
						},
					)
				}
			}
		},
	)
}
