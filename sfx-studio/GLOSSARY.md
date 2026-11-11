# GLOSSARY: SuperFX Asset & Level Studio (`sfx-studio`)

Authoring studio and offline asset pipeline for creating, editing, validating, and exporting Texture Atlases and tactical Levels targeting the SuperFX isometric engine.

## Language

### Documents

**Atlas Document**:
The authoring specification for a single Texture Atlas, holding its color palette, Source Textures, layout positions, Virtual Slices, and Descriptor Aliases so multiple Level Documents can share one atlas.
_Avoid_: Project file, texture project, atlas manifest

**Level Document**:
The authoring specification for a single Level, combining its Map Grid surface geometry and cell-aligned gameplay layers with a reference to a shared Atlas Document.
_Avoid_: Map project, stage file, scene file

### Texture Authoring & Export

**Source Texture**:
An individual power-of-two graphic imported or drawn in the studio to be quantized, edited, and positioned within a Texture Atlas.
_Avoid_: Imported tile, bitmap graphic, source image

**Virtual Slice**:
A secondary region defined as a sub-rectangle of an existing Source Texture within an Atlas Document, sharing texel data without occupying additional space in the Texture Atlas.
_Avoid_: Sub-texture, texture slice, crop region

**Descriptor Alias**:
An alternate symbolic identifier in an Atlas Document that resolves to the same runtime Texture Descriptor as another texture or Virtual Slice.
_Avoid_: Texture alias, duplicate ID, linked texture

**Atlas Index**:
The exported symbolic catalog describing a Texture Atlas's dimensions and each entry's identifier, coordinates, dimensions, and offset for consumption by the runtime engine.
_Avoid_: Index file, texture header
