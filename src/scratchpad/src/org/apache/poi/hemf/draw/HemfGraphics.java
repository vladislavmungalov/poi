/* ====================================================================
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
==================================================================== */

package org.apache.poi.hemf.draw;

import static org.apache.poi.hwmf.record.HwmfBrushStyle.BS_NULL;
import static org.apache.poi.hwmf.record.HwmfBrushStyle.BS_SOLID;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import org.apache.poi.hemf.draw.HemfDrawProperties.TransOperand;
import org.apache.poi.hemf.record.emf.HemfComment.EmfComment;
import org.apache.poi.hemf.record.emf.HemfRecord;
import org.apache.poi.hemf.record.emfplus.HemfPlusRecord;
import org.apache.poi.hwmf.draw.HwmfDrawProperties;
import org.apache.poi.hwmf.draw.HwmfGraphics;
import org.apache.poi.hwmf.record.HwmfColorRef;
import org.apache.poi.hwmf.record.HwmfObjectTableEntry;
import org.apache.poi.hwmf.record.HwmfPenStyle;
import org.apache.poi.util.Internal;

public class HemfGraphics extends HwmfGraphics {

    public enum EmfRenderState {
        INITIAL,
        EMF_ONLY,
        EMFPLUS_ONLY,
        EMF_DCONTEXT
    }

    private static final HwmfColorRef WHITE = new HwmfColorRef(Color.WHITE);
    private static final HwmfColorRef LTGRAY = new HwmfColorRef(new Color(0x00C0C0C0));
    private static final HwmfColorRef GRAY = new HwmfColorRef(new Color(0x00808080));
    private static final HwmfColorRef DKGRAY = new HwmfColorRef(new Color(0x00404040));
    private static final HwmfColorRef BLACK = new HwmfColorRef(Color.BLACK);

    private EmfRenderState renderState = EmfRenderState.INITIAL;

    public HemfGraphics(Graphics2D graphicsCtx, Rectangle2D bbox) {
        super(graphicsCtx,bbox);
        // add dummy entry for object ind ex 0, as emf is 1-based
        objectIndexes.set(0);
    }

    @Override
    public HemfDrawProperties getProperties() {
        return (HemfDrawProperties)super.getProperties();
    }

    @Override
    protected HemfDrawProperties newProperties(HwmfDrawProperties oldProps) {
        return (oldProps == null)
            ? new HemfDrawProperties()
            : new HemfDrawProperties((HemfDrawProperties)oldProps);
    }

    public EmfRenderState getRenderState() {
        return renderState;
    }

    public void setRenderState(EmfRenderState renderState) {
        this.renderState = renderState;
    }

    public void draw(HemfRecord r) {
        switch (getRenderState()) {
            case EMF_DCONTEXT:
                // This state specifies that subsequent EMF records encountered in the metafile SHOULD be processed.
                // EMF records cease being processed when the next EMF+ record is encountered.
                if (r instanceof EmfComment) {
                    setRenderState(EmfRenderState.EMFPLUS_ONLY);
                }
                r.draw(this);
                break;
            case INITIAL:
                r.draw(this);
                break;
            case EMF_ONLY:
                if (!(r instanceof EmfComment)) {
                    r.draw(this);
                }
                break;
            case EMFPLUS_ONLY:
                if (r instanceof EmfComment) {
                    r.draw(this);
                }
                break;
            default:
                break;
        }
    }

    public void draw(HemfPlusRecord r) {
        r.draw(this);
    }

    @Internal
    public void draw(Consumer<Path2D> pathConsumer, FillDrawStyle fillDraw) {
        final HemfDrawProperties prop = getProperties();
        final boolean useBracket = prop.getUsePathBracket();

        final Path2D path;
        if (useBracket) {
            path = prop.getPath();
        } else {
            path = new Path2D.Double();
            path.setWindingRule(prop.getWindingRule());
        }

        // add dummy move-to at start, to handle invalid emfs not containing that move-to
        if (path.getCurrentPoint() == null) {
            Point2D pnt = prop.getLocation();
            path.moveTo(pnt.getX(), pnt.getY());
        }

        try {
            pathConsumer.accept(path);
        } catch (Exception e) {
            // workaround if a path has been started and no MoveTo command
            // has been specified before the first lineTo/splineTo
            final Point2D loc = prop.getLocation();
            path.moveTo(loc.getX(), loc.getY());
            pathConsumer.accept(path);
        }

        Point2D curPnt = path.getCurrentPoint();
        if (curPnt == null) {
            return;
        }

        prop.setLocation(curPnt);
        if (!useBracket) {
            switch (fillDraw) {
                case FILL:
                    super.fill(path);
                    break;
                case DRAW:
                    super.draw(path);
                    break;
                case FILL_DRAW:
                    super.fill(path);
                    super.draw(path);
                    break;
            }
        }

    }

    /**
     * Adds or sets an record of type {@link HwmfObjectTableEntry} to the object table.
     * If the {@code index} is less than 1, the method acts the same as
     * {@link HwmfGraphics#addObjectTableEntry(HwmfObjectTableEntry)}, otherwise the
     * index is used to access the object table.
     * As the table is filled successively, the index must be between 1 and size+1
     *
     * @param entry the record to be stored
     * @param index the index to be overwritten, regardless if its content was unset before
     *
     * @see HwmfGraphics#addObjectTableEntry(HwmfObjectTableEntry)
     */
    public void addObjectTableEntry(HwmfObjectTableEntry entry, int index) {
        checkTableEntryIndex(index);
        objectIndexes.set(index);
        objectTable.put(index, entry);
    }

    /**
     * Gets a record which was registered earliser
     * @param index the record index
     * @return the record or {@code null} if it doesn't exist
     */
    public HwmfObjectTableEntry getObjectTableEntry(int index) {
        checkTableEntryIndex(index);
        return objectTable.get(index);
    }

    private void checkTableEntryIndex(int index) {
        if (renderState != EmfRenderState.EMFPLUS_ONLY && renderState != EmfRenderState.EMF_DCONTEXT) {
            // in EMF the index must > 0
            if (index < 1) {
                throw new IndexOutOfBoundsException("Object table entry index in EMF must be > 0 - invalid index: "+index);
            }
        } else {
            // in EMF+ the index must be between 0 and 63
            if (index < 0 || index > 63) {
                throw new IndexOutOfBoundsException("Object table entry index in EMF+ must be [0..63] - invalid index: "+index);
            }
        }
    }


    @Override
    public void applyObjectTableEntry(int index) {
        if ((index & 0x80000000) != 0) {
            selectStockObject(index);
        } else {
            super.applyObjectTableEntry(index);
        }
    }

    private void selectStockObject(int objectIndex) {
        final HemfDrawProperties prop = getProperties();
        switch (objectIndex) {
            case 0x80000000:
                // WHITE_BRUSH - A white, solid-color brush
                // BrushStyle: BS_SOLID
                // Color: 0x00FFFFFF
                prop.setBrushColor(WHITE);
                prop.setBrushStyle(BS_SOLID);
                break;
            case 0x80000001:
                // LTGRAY_BRUSH - A light gray, solid-color brush
                // BrushStyle: BS_SOLID
                // Color: 0x00C0C0C0
                prop.setBrushColor(LTGRAY);
                prop.setBrushStyle(BS_SOLID);
                break;
            case 0x80000002:
                // GRAY_BRUSH - A gray, solid-color brush
                // BrushStyle: BS_SOLID
                // Color: 0x00808080
                prop.setBrushColor(GRAY);
                prop.setBrushStyle(BS_SOLID);
                break;
            case 0x80000003:
                // DKGRAY_BRUSH - A dark gray, solid color brush
                // BrushStyle: BS_SOLID
                // Color: 0x00404040
                prop.setBrushColor(DKGRAY);
                prop.setBrushStyle(BS_SOLID);
                break;
            case 0x80000004:
                // BLACK_BRUSH - A black, solid color brush
                // BrushStyle: BS_SOLID
                // Color: 0x00000000
                prop.setBrushColor(BLACK);
                prop.setBrushStyle(BS_SOLID);
                break;
            case 0x80000005:
                // NULL_BRUSH - A null brush
                // BrushStyle: BS_NULL
                prop.setBrushStyle(BS_NULL);
                break;
            case 0x80000006:
                // WHITE_PEN - A white, solid-color pen
                // PenStyle: PS_COSMETIC + PS_SOLID
                // ColorRef: 0x00FFFFFF
                prop.setPenStyle(HwmfPenStyle.valueOf(0));
                prop.setPenWidth(1);
                prop.setPenColor(WHITE);
                break;
            case 0x80000007:
                // BLACK_PEN - A black, solid-color pen
                // PenStyle: PS_COSMETIC + PS_SOLID
                // ColorRef: 0x00000000
                prop.setPenStyle(HwmfPenStyle.valueOf(0));
                prop.setPenWidth(1);
                prop.setPenColor(BLACK);
                break;
            case 0x80000008:
                // NULL_PEN - A null pen
                // PenStyle: PS_NULL
                prop.setPenStyle(HwmfPenStyle.valueOf(HwmfPenStyle.HwmfLineDash.NULL.wmfFlag));
                break;
            case 0x8000000A:
                // OEM_FIXED_FONT - A fixed-width, OEM character set
                // Charset: OEM_CHARSET
                // PitchAndFamily: FF_DONTCARE + FIXED_PITCH
                break;
            case 0x8000000B:
                // ANSI_FIXED_FONT - A fixed-width font
                // Charset: ANSI_CHARSET
                // PitchAndFamily: FF_DONTCARE + FIXED_PITCH
                break;
            case 0x8000000C:
                // ANSI_VAR_FONT - A variable-width font
                // Charset: ANSI_CHARSET
                // PitchAndFamily: FF_DONTCARE + VARIABLE_PITCH
                break;
            case 0x8000000D:
                // SYSTEM_FONT - A font that is guaranteed to be available in the operating system
                break;
            case 0x8000000E:
                // DEVICE_DEFAULT_FONT
                // The default font that is provided by the graphics device driver for the current output device
                break;
            case 0x8000000F:
                // DEFAULT_PALETTE
                // The default palette that is defined for the current output device.
                break;
            case 0x80000010:
                // SYSTEM_FIXED_FONT
                // A fixed-width font that is guaranteed to be available in the operating system.
                break;
            case 0x80000011:
                // DEFAULT_GUI_FONT
                // The default font that is used for user interface objects such as menus and dialog boxes.
                break;
            case 0x80000012:
                // DC_BRUSH
                // The solid-color brush that is currently selected in the playback device context.
                break;
            case 0x80000013:
                // DC_PEN
                // The solid-color pen that is currently selected in the playback device context.
                break;
        }
    }

    @Override
    protected Paint getHatchedFill() {
        // TODO: use EmfPlusHatchBrushData
        return super.getHatchedFill();
    }

    @Override
    public void updateWindowMapMode() {
        super.updateWindowMapMode();
        HemfDrawProperties prop = getProperties();

        List<AffineTransform> transXform = prop.getTransXForm();
        List<TransOperand> transOper = prop.getTransOper();
        assert(transXform.size() == transOper.size());

        AffineTransform tx = graphicsCtx.getTransform();
        Iterator<AffineTransform> iter = transXform.iterator();
        transOper.forEach(to -> to.fun.accept(tx, iter.next()));

        graphicsCtx.setTransform(tx);
    }
}
