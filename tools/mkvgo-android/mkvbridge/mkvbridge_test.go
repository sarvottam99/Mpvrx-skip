package mkvbridge

import (
	"errors"
	"github.com/gravity-zero/mkvgo/matroska"
	"testing"
)

func TestChapterTimingFirst(t *testing.T) {
	start, end, err := chapterTiming(
		120000,
		chapterRequest{
			Mode:    "first",
			Minutes: 1,
			Seconds: 18,
		},
	)

	if err != nil {
		t.Fatal(err)
	}

	if start != 0 || end != 78000 {
		t.Fatalf("got %d -> %d, want 0 -> 78000", start, end)
	}
}

func TestChapterTimingLast(t *testing.T) {
	start, end, err := chapterTiming(
		2700000,
		chapterRequest{
			Mode:    "last",
			Minutes: 2,
			Seconds: 0,
		},
	)

	if err != nil {
		t.Fatal(err)
	}

	if start != 2580000 || end != 2700000 {
		t.Fatalf("got %d -> %d, want 2580000 -> 2700000", start, end)
	}
}

func TestChapterTimingRange(t *testing.T) {
	start, end, err := chapterTiming(
		600000,
		chapterRequest{
			Mode:         "range",
			StartMinutes: 3,
			StartSeconds: 0,
			EndMinutes:   5,
			EndSeconds:   0,
		},
	)

	if err != nil {
		t.Fatal(err)
	}

	if start != 180000 || end != 300000 {
		t.Fatalf("got %d -> %d, want 180000 -> 300000", start, end)
	}
}

func TestChapterTimingRangeToDuration(t *testing.T) {
	for _, durationMs := range []int64{600000, 2700000} {
		start, end, err := chapterTiming(
			durationMs,
			chapterRequest{
				Mode:          "range",
				StartMinutes:  1,
				StartSeconds:  17,
				EndAtDuration: true,
			},
		)

		if err != nil {
			t.Fatalf("duration %d: %v", durationMs, err)
		}
		if start != 77000 || end != durationMs {
			t.Fatalf(
				"duration %d: got %d -> %d, want 77000 -> %d",
				durationMs, start, end, durationMs,
			)
		}
	}
}

func TestChapterTimingClampsToDuration(t *testing.T) {
	start, end, err := chapterTiming(
		240000,
		chapterRequest{
			Mode:         "range",
			StartMinutes: 3,
			StartSeconds: 0,
			EndMinutes:   10,
			EndSeconds:   0,
		},
	)

	if err != nil {
		t.Fatal(err)
	}

	if start != 180000 || end != 240000 {
		t.Fatalf("got %d -> %d, want 180000 -> 240000", start, end)
	}
}

func TestChapterTimingRejectsInvalidRange(t *testing.T) {
	_, _, err := chapterTiming(
		600000,
		chapterRequest{
			Mode:         "range",
			StartMinutes: 5,
			EndMinutes:   3,
		},
	)

	if err == nil {
		t.Fatal("expected invalid range error")
	}
}

func TestChapterTimingRejectsInvalidMode(t *testing.T) {
	_, _, err := chapterTiming(
		600000,
		chapterRequest{
			Mode: "invalid",
		},
	)

	if err == nil {
		t.Fatal("expected invalid mode error")
	}
}

func TestChapterTimingRejectsZeroLength(t *testing.T) {
	_, _, err := chapterTiming(
		600000,
		chapterRequest{
			Mode: "first",
		},
	)

	if err == nil {
		t.Fatal("expected zero-length chapter error")
	}
}

func TestChapterTimingLastClampsRequestedLength(t *testing.T) {
	start, end, err := chapterTiming(
		60000,
		chapterRequest{
			Mode:    "last",
			Minutes: 2,
		},
	)

	if err != nil {
		t.Fatal(err)
	}

	if start != 0 || end != 60000 {
		t.Fatalf("got %d -> %d, want 0 -> 60000", start, end)
	}
}

func TestChapterTimingFirstClampsToDuration(t *testing.T) {
	start, end, err := chapterTiming(
		60000,
		chapterRequest{
			Mode:    "first",
			Minutes: 2,
		},
	)

	if err != nil {
		t.Fatal(err)
	}

	if start != 0 || end != 60000 {
		t.Fatalf("got %d -> %d, want 0 -> 60000", start, end)
	}
}

func TestChapterTimingLastClampsToDuration(t *testing.T) {
	start, end, err := chapterTiming(
		60000,
		chapterRequest{
			Mode:    "last",
			Minutes: 2,
		},
	)

	if err != nil {
		t.Fatal(err)
	}

	if start != 0 || end != 60000 {
		t.Fatalf("got %d -> %d, want 0 -> 60000", start, end)
	}
}

func TestChapterTimingRejectsInvalidSeconds(t *testing.T) {
	_, _, err := chapterTiming(
		600000,
		chapterRequest{
			Mode:    "first",
			Minutes: 1,
			Seconds: 60,
		},
	)
	if err == nil {
		t.Fatal("expected seconds outside 0..59 to be rejected")
	}
}

func TestChapterTimingRejectsNegativeRangeMinutes(t *testing.T) {
	_, _, err := chapterTiming(
		600000,
		chapterRequest{
			Mode:         "range",
			StartMinutes: -1,
			EndMinutes:   2,
		},
	)
	if err == nil {
		t.Fatal("expected negative chapter minutes to be rejected")
	}
}

func TestNeedsFullRewrite(t *testing.T) {
	tests := []struct {
		name string
		err  error
		want bool
	}{
		{
			name: "metadata region too small",
			err:  errors.New("new metadata exceeds available space - use full rewrite instead"),
			want: true,
		},
		{
			name: "unfillable one-byte gap",
			err:  errors.New("new metadata leaves exactly 1 byte which no EBML element can fill"),
			want: true,
		},
		{
			name: "ordinary I/O error",
			err:  errors.New("permission denied"),
			want: false,
		},
		{
			name: "nil error",
			err:  nil,
			want: false,
		},
	}

	for _, tt := range tests {
		tt := tt
		t.Run(tt.name, func(t *testing.T) {
			if got := needsFullRewrite(tt.err); got != tt.want {
				t.Fatalf("needsFullRewrite(%v) = %v, want %v", tt.err, got, tt.want)
			}
		})
	}
}

func TestChapterReplacementAtExistingStart(t *testing.T) {
	chapters := []matroska.Chapter{
		{
			Title:   "Intro",
			StartMs: 0,
			EndMs:   3240000,
		},
		{
			Title:   "Chapter 2",
			StartMs: 3144450,
			EndMs:   3295000,
		},
	}

	newChapter := matroska.Chapter{
		Title:   "Intro",
		StartMs: 0,
		EndMs:   77000,
	}

	updated := upsertChapter(chapters, newChapter)

	if len(updated) != 2 {
		t.Fatalf("got %d chapters, want 2", len(updated))
	}

	if updated[0].Title != "Intro" ||
		updated[0].StartMs != 0 ||
		updated[0].EndMs != 77000 {
		t.Fatalf("first chapter was not replaced correctly: %+v", updated[0])
	}

	if updated[1].Title != "Chapter 2" ||
		updated[1].StartMs != 3144450 {
		t.Fatalf("existing second chapter was changed: %+v", updated[1])
	}
}

func TestChapterInsertWhenStartDoesNotExist(t *testing.T) {
	chapters := []matroska.Chapter{
		{
			Title:   "Chapter 1",
			StartMs: 0,
			EndMs:   100000,
		},
		{
			Title:   "Chapter 2",
			StartMs: 3144450,
			EndMs:   3295000,
		},
	}

	newChapter := matroska.Chapter{
		Title:   "Intro",
		StartMs: 77000,
		EndMs:   120000,
	}

	updated := upsertChapter(chapters, newChapter)

	if len(updated) != 3 {
		t.Fatalf("got %d chapters, want 3", len(updated))
	}

	if updated[1].Title != "Intro" ||
		updated[1].StartMs != 77000 ||
		updated[1].EndMs != 120000 {
		t.Fatalf("new chapter was not inserted correctly: %+v", updated[1])
	}
}

func TestChapterInsertWithNoExistingChapters(t *testing.T) {
	newChapter := matroska.Chapter{
		Title:   "Intro",
		StartMs: 0,
		EndMs:   77000,
	}

	updated := upsertChapter(nil, newChapter)

	if len(updated) != 1 {
		t.Fatalf("got %d chapters, want 1", len(updated))
	}

	if updated[0].Title != "Intro" ||
		updated[0].StartMs != 0 ||
		updated[0].EndMs != 77000 {
		t.Fatalf("chapter was not inserted correctly: %+v", updated[0])
	}
}

func TestChapterDuplicateStartCollapsesToOne(t *testing.T) {
	chapters := []matroska.Chapter{
		{
			Title:   "Old Intro",
			StartMs: 0,
			EndMs:   60000,
		},
		{
			Title:   "Duplicate Intro",
			StartMs: 0,
			EndMs:   70000,
		},
		{
			Title:   "Chapter 2",
			StartMs: 3144450,
			EndMs:   3295000,
		},
	}

	newChapter := matroska.Chapter{
		Title:   "Intro",
		StartMs: 0,
		EndMs:   77000,
	}

	updated := upsertChapter(chapters, newChapter)

	if len(updated) != 2 {
		t.Fatalf("got %d chapters, want 2", len(updated))
	}

	if updated[0].Title != "Intro" ||
		updated[0].StartMs != 0 ||
		updated[0].EndMs != 77000 {
		t.Fatalf("duplicate start was not collapsed correctly: %+v", updated[0])
	}
}

func TestChapterReplacementPreservesID(t *testing.T) {
	existing := []matroska.Chapter{
		{ID: 123, Title: "Chapter 1", StartMs: 0, EndMs: 3240000},
		{ID: 456, Title: "Chapter 2", StartMs: 3144450, EndMs: 3295520},
	}

	result := upsertChapter(existing, matroska.Chapter{
		Title: "Intro", StartMs: 0, EndMs: 77000,
	})

	if len(result) != 2 {
		t.Fatalf("got %d chapters, want 2", len(result))
	}
	if result[0].ID != 123 || result[0].Title != "Intro" || result[0].EndMs != 77000 {
		t.Fatalf("replacement incorrect: %+v", result[0])
	}
	if result[1].ID != 456 || result[1].Title != "Chapter 2" {
		t.Fatalf("existing chapter changed: %+v", result[1])
	}
}

func TestChapterInsertGetsUniqueNonzeroID(t *testing.T) {
	existing := []matroska.Chapter{
		{ID: 123, Title: "Chapter 1", StartMs: 0},
		{ID: 456, Title: "Chapter 2", StartMs: 3144450},
	}

	result := upsertChapter(existing, matroska.Chapter{
		Title: "Intro", StartMs: 77000, EndMs: 120000,
	})

	if len(result) != 3 {
		t.Fatalf("got %d chapters, want 3", len(result))
	}
	if result[1].ID == 0 || result[1].ID == 123 || result[1].ID == 456 {
		t.Fatalf("new chapter ID is not unique and nonzero: %d", result[1].ID)
	}
}
