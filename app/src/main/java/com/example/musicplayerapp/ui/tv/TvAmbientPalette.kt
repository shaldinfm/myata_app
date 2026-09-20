package com.example.musicplayerapp.ui.tv

/**
 * One candidate colour from the current cover, in the only form the ambient
 * policy needs: the colour, and how much of the artwork it came from.
 *
 * Population is carried rather than thrown away because "do not blindly use
 * every swatch" has to be checkable somewhere. A colour covering 0.4% of a
 * sleeve is a highlight on a logo, not an area the artwork is made of, and
 * painting a third of the screen with it would invent an area that is not there.
 */
data class TvAmbientSwatch(val argb: Int, val population: Int)

/**
 * The colours the TV player's ambient field is drawn in, one entry per blob.
 *
 * A value type, and deliberately so: the view decides whether a new palette is
 * worth a crossfade by comparing it with the one it is showing
 * (`TvAmbientPalette` equality), which is what stops a metadata tick that
 * repeats the current track - or a new cover that reduces to the same four
 * colours - from restarting an animation nobody asked to restart.
 *
 * [isFallback] is part of that identity because the brand field is a different
 * statement from a field derived from artwork, even in the unlikely case that
 * the two reduce to identical colours.
 */
data class TvAmbientPalette(val colors: List<Int>, val isFallback: Boolean)
