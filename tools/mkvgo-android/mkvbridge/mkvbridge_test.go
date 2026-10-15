package mkvbridge

import "testing"

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
