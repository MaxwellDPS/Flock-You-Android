# Detection Info Page UX Improvement Plan

## Overview
Comprehensive UX improvements for the Flock-You detection info page to enhance usability, information hierarchy, and user engagement.

---

## Phase 1: Core UX Improvements

### 1.1 Information Hierarchy Refactor
**Files:** `MainScreen.kt`, `Components.kt`

**Changes:**
- Restructure `DetectionDetailSheet` into clear collapsible sections
- Create `DetectionSummarySection` - always visible quick summary
- Create `DetectionTechnicalSection` - collapsible technical details
- Add section headers with expand/collapse state persistence
- Implement smart defaults (AI analysis expanded if available, technical collapsed)

**New Components:**
```kotlin
@Composable
fun CollapsibleSection(
    title: String,
    icon: ImageVector,
    defaultExpanded: Boolean,
    persistKey: String,
    content: @Composable () -> Unit
)
```

### 1.2 Visual Threat Header
**Files:** `MainScreen.kt`, `Components.kt`

**Changes:**
- Add gradient header based on threat level (full-width color bar)
- Larger threat badge with animated icon for critical threats
- Add threat context text ("High severity - surveillance device")
- Pulsing animation for active critical detections

**New Components:**
```kotlin
@Composable
fun ThreatHeader(
    threatLevel: ThreatLevel,
    threatScore: Int,
    isActive: Boolean,
    deviceType: DeviceType
)
```

---

## Phase 2: Timeline & History

### 2.1 Detection Timeline Component
**Files:** New `DetectionTimeline.kt` in `ui/components/`

**Features:**
- Horizontal timeline showing detection events
- Visual dots/bars for each detection
- Signal strength variation over time
- Time labels (first seen, last seen, peak activity)
- Zoomable for long detection histories

**New Components:**
```kotlin
@Composable
fun DetectionTimeline(
    detections: List<DetectionEvent>,
    modifier: Modifier = Modifier
)

@Composable
fun SignalStrengthChart(
    readings: List<SignalReading>,
    modifier: Modifier = Modifier
)
```

### 2.2 Activity Heatmap (Optional)
**Files:** New `ActivityHeatmap.kt`

**Features:**
- 7x24 grid showing detection frequency by day/hour
- Color intensity based on detection count
- Useful for identifying surveillance patterns

---

## Phase 3: Enhanced Actions

### 3.1 Sticky Action Bar
**Files:** `MainScreen.kt`

**Changes:**
- Add sticky bottom action bar to detail sheet
- Primary actions: Mark Safe, Mark Threat, Share
- Secondary actions in overflow menu

**New Components:**
```kotlin
@Composable
fun DetectionActionBar(
    detection: Detection,
    onMarkSafe: () -> Unit,
    onMarkThreat: () -> Unit,
    onShare: () -> Unit,
    onNavigate: () -> Unit,
    onAddNote: () -> Unit
)
```

### 3.2 Swipe Navigation
**Files:** `MainScreen.kt`

**Changes:**
- Implement HorizontalPager for detection detail sheet
- Swipe left/right to navigate between detections
- Page indicator dots
- Preserve scroll position when returning to list

### 3.3 User Notes/Annotations
**Files:** `Detection.kt`, `DetectionDao.kt`, `MainScreen.kt`

**Changes:**
- Add `userNote: String?` field to Detection entity
- Add note input dialog
- Display note in detail view
- Searchable notes in history

---

## Phase 4: Full-Screen Detail View

### 4.1 Full-Screen Detection Screen
**Files:** New `DetectionDetailScreen.kt` in `ui/screens/`

**Features:**
- Dedicated route: `detection/{detectionId}`
- Full map integration showing detection location
- Complete timeline with all historical data
- Related detections section
- All technical data in organized tabs

**Navigation:**
```kotlin
composable("detection/{detectionId}") { backStackEntry ->
    val detectionId = backStackEntry.arguments?.getString("detectionId")
    DetectionDetailScreen(detectionId = detectionId)
}
```

### 4.2 Deep Linking
**Files:** `AndroidManifest.xml`, `MainActivity.kt`

**Changes:**
- Add intent filter for `flockyou://detection/{id}`
- Handle deep link navigation
- Share detection link functionality

---

## Phase 5: Related Detections

### 5.1 Related Detections Section
**Files:** `MainScreen.kt`, `DetectionRepository.kt`

**Features:**
- Query for related detections (same MAC, similar location, similar time)
- Horizontal scrollable cards showing related items
- "See all related" expansion

**New Components:**
```kotlin
@Composable
fun RelatedDetectionsSection(
    currentDetection: Detection,
    relatedDetections: List<Detection>,
    onDetectionClick: (Detection) -> Unit
)
```

### 5.2 Detection Clustering
**Files:** `DetectionRepository.kt`

**Queries:**
```kotlin
fun getDetectionsByMac(mac: String): Flow<List<Detection>>
fun getDetectionsNearLocation(lat: Double, lng: Double, radiusMeters: Double): Flow<List<Detection>>
fun getDetectionsInTimeRange(start: Long, end: Long): Flow<List<Detection>>
```

---

## Phase 6: Polish & Accessibility

### 6.1 Accessibility Improvements
- Content descriptions for all icons and threat indicators
- Proper heading hierarchy for screen readers
- Dynamic text size support
- High contrast mode for threat colors

### 6.2 Animations & Feedback
- Smooth expand/collapse animations (already partial)
- Haptic feedback for actions
- Loading states for async operations
- Success/error feedback animations

### 6.3 Remove Simple/Advanced Mode Fragmentation
- Unified experience with progressive disclosure
- Remember user's section expansion preferences
- Context-aware defaults

---

## Implementation Priority

| Priority | Feature | Impact | Effort |
|----------|---------|--------|--------|
| P0 | Information Hierarchy Refactor | High | Medium |
| P0 | Visual Threat Header | High | Low |
| P1 | Sticky Action Bar | High | Low |
| P1 | Detection Timeline | High | Medium |
| P2 | Full-Screen Detail View | Medium | High |
| P2 | Deep Linking | Medium | Medium |
| P2 | Related Detections | Medium | Medium |
| P3 | User Notes | Low | Low |
| P3 | Activity Heatmap | Low | Medium |
| P3 | Swipe Navigation | Low | Medium |

---

## File Changes Summary

### New Files
- `app/src/main/java/com/flockyou/ui/components/DetectionTimeline.kt`
- `app/src/main/java/com/flockyou/ui/components/ThreatHeader.kt`
- `app/src/main/java/com/flockyou/ui/components/CollapsibleSection.kt`
- `app/src/main/java/com/flockyou/ui/components/DetectionActionBar.kt`
- `app/src/main/java/com/flockyou/ui/components/RelatedDetections.kt`
- `app/src/main/java/com/flockyou/ui/screens/DetectionDetailScreen.kt`

### Modified Files
- `app/src/main/java/com/flockyou/ui/screens/MainScreen.kt`
- `app/src/main/java/com/flockyou/ui/components/Components.kt`
- `app/src/main/java/com/flockyou/data/Detection.kt`
- `app/src/main/java/com/flockyou/data/DetectionDao.kt`
- `app/src/main/java/com/flockyou/data/DetectionRepository.kt`
- `app/src/main/java/com/flockyou/MainActivity.kt`
- `app/src/main/AndroidManifest.xml`

---

## Success Metrics
- Reduced time to understand threat severity
- Increased use of mark safe/threat actions
- Reduced support questions about detection details
- Improved accessibility audit scores
