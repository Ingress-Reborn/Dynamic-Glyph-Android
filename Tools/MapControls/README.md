# Map control calibration

`generate-patterns.py` takes the 1216×2688 map frame at 19 seconds in the user-provided `Screenrecorder-20261109-124013.mp4`. The original recording and full map frame remain local evidence and are not included here.

The five crop centres are independent: compass diamond, signal arcs, layers, square plus, and briefcase. The three controls on the right are never a single template. Only the central symbol and black surround enter each sparse pattern; roads, text, labels, the player name, and nearby controls do not. Sixteen compass rotations cover changes in needle direction.

Regenerate the Kotlin pattern source and the five isolated colour test rasters together:

```powershell
python Tools/MapControls/generate-patterns.py path/to/map-19.png
```

Edit the generator or source calibration before regenerating; do not hand-edit `MapControlPatterns.kt`. Runtime matching locates an individual bright connected component and estimates its scale, so the source crop coordinates do not become fixed screen coordinates. The full-colour test rasters are separately rescaled, moved, and rotated in tests; they are not drawn from the detector's sample coordinates.

Downscaling can join thin, originally separate parts of an icon. The four white controls therefore accept either their principal connected component or the complete symbol as a scale anchor; both anchors still require the same sampled shape. Positive samples preserve the identifying details: both signal arcs and its body, the lower layer outlines, the central plus, and the case handle. Signal, layer, and handle gaps reject filled shapes. The final layer must also contribute bright samples, so a filled case cannot pass by consuming all the missing-sample allowance.

Search runs at most four times per second, in the screen edges where these controls appear. A successful location is checked with sparse samples on subsequent frames. Losing the icon immediately removes that match; it does not reuse a previous positive result while waiting for the next search. Finding any one control is sufficient, including on the initial map screen before a Glyph grid has been calibrated.

The four circular controls also require a black surround at eight directions outside the symbol. At least six samples must have all RGB channels at or below 8; a sample may move one pixel towards the centre to avoid the antialiased rim. The reference control backgrounds measure 0–2, while ordinary dark map backgrounds can measure 17–24. The two remaining samples accommodate the briefcase's lower corners at small scales. This rejects combinations of a window's white close button and its separate border, even when the border is neutral grey. The square plus uses its own frame geometry instead.

The compass samples require both its red needle and the grey diamond base, because coloured text or small red map marks can resemble only the needle. Signal, layers and briefcase require neutral white strokes with at most 40 levels between colour channels; cyan or pale-purple window borders cannot substitute for the lower outline of a layers icon. The plus retains its coloured square frame. Signal verification also requires its white intensity, which separates it from grey disabled drawing labels. Local failure screenshots were reduced to isolated prediction-window, input-window, inactive-window and disabled-button test fixtures; no account, status-bar or map-photo pixels are included. Three fixtures preserve each window's original position on a black full-size canvas, so downscaling to 603×1333 or 603×1332 and shifting by one capture pixel exercises the actual sampling phase.
