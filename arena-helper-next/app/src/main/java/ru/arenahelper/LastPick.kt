package ru.arenahelper

/** A last pick has no subsequent offer to trigger the ordinary pick counter. */
class LastPick {
    private var offerFrames=0
    private var selectionAt=-1L
    private var missingSince=-1L
    private var missingFrames=0
    @Synchronized fun reset() {
        offerFrames=0;selectionAt=-1;missingSince=-1;missingFrames=0
    }
    @Synchronized fun observe(count:Int, visible:Int, selection:Boolean, boardVisible:Boolean, now:Long):Boolean {
        if(count!=29 || !boardVisible) {reset();return false}
        if(visible>=2) {
            offerFrames=(offerFrames+1).coerceAtMost(2)
            missingFrames=0;missingSince=-1
        }
        if(offerFrames<2) return false
        if(selection) selectionAt=now
        if(visible!=0 || selectionAt<0 || now-selectionAt>8000) return false
        if(missingSince<0) missingSince=now
        missingFrames++
        if(missingFrames<3 || now-missingSince<1000) return false
        reset()
        return true
    }
}
