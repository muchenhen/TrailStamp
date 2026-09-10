# Bundled third-party software

GPSLog embeds the official 64-bit Windows ExifTool 13.59 distribution archive so RAW operations never download code at runtime.

- Project: ExifTool by Phil Harvey
- Archive: `exiftool-13.59_64.zip`
- Official SHA-256: `44b512b25af500724ba579d0a53c8fc5851628b692dd5e5d94ae4a15c2cba9ec`
- Source and licensing: <https://exiftool.org/>

ExifTool is distributed under the same terms as Perl (Artistic License or GNU General Public License). The Windows archive also contains Strawberry Perl components and their license bundle under `exiftool_files`. Those original license files are preserved inside the embedded archive and in the versioned runtime cache after extraction.
