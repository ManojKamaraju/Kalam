#!/usr/bin/env python3
"""Builds the bundled font library for KALAM.

Source: the google/fonts repository (SIL Open Font License / Apache 2.0 families only).
Each font is subset to Basic Latin + Latin-1 (digits, punctuation and letters are all
the app needs) so the whole library stays small. Variable fonts keep their variation axes.
Output: app/src/main/assets/fonts/*.ttf, manifest.json and LICENSES.txt
"""
import json, os, sys, glob, shutil
from fontTools.ttLib import TTFont
from fontTools import subset

ROOT = sys.argv[1] if len(sys.argv) > 1 else "google-fonts"
OUT = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets/fonts"

CATEGORIES = {
    "Sans-serif": "roboto opensans lato notosans firasans ubuntu rubik archivo titilliumweb kanit barlow exo2",
    "Minimal": "inter worksans dmsans manrope",
    "Modern": "raleway syne spacegrotesk urbanist sora outfit",
    "Geometric": "jost josefinsans lexend montserrat poppins",
    "Rounded": "quicksand varelaround comfortaa fredoka nunito",
    "Serif": "playfairdisplay merriweather lora cormorantgaramond ebgaramond librebaskerville crimsontext bitter spectral notoserif robotoslab dmserifdisplay",
    "Monospaced": "robotomono jetbrainsmono firacode spacemono ibmplexmono sourcecodepro sharetechmono vt323 majormonodisplay",
    "Display": "bebasneue oswald anton abrilfatface righteous bungee audiowide russoone teko rajdhani saira orbitron syncopate unbounded michroma chakrapetch blackopsone fasterone rampartone",
    "Decorative": "pacifico lobster dancingscript caveat shadowsintolight pressstart2p specialelite",
    "Outline": "monoton bungeeoutline bungeeinline fascinateinline codystar londrinaoutline",
}
FAM_CAT = {}
for cat, ids in CATEGORIES.items():
    for i in ids.split():
        FAM_CAT[i] = cat

if len(sys.argv) > 1 and sys.argv[1] == "--list":
    # paths for `git sparse-checkout set` (used by tools/fetch_fonts.sh)
    print(" ".join("%s/%s" % (lic, fid) for fid in sorted(FAM_CAT) for lic in ("ofl", "apache", "ufl")))
    sys.exit(0)

UNICODES = list(range(0x20, 0x7F)) + list(range(0xA0, 0x100)) + [0x2013, 0x2014, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2026]


def meta_name(path, fallback):
    try:
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if line.startswith("name:"):
                return line.split('"')[1]
    except Exception:
        pass
    return fallback


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)
    families = []
    licenses = []
    for fid in sorted(FAM_CAT):
        fdir = None
        for lic in ("ofl", "apache", "ufl"):
            p = os.path.join(ROOT, lic, fid)
            if os.path.isdir(p):
                fdir = p
                break
        if not fdir:
            print("missing family", fid)
            continue
        ttfs = sorted(glob.glob(os.path.join(fdir, "*.ttf")))
        files = []
        for t in ttfs:
            base = os.path.basename(t)
            try:
                font = TTFont(t)
                italic = ("Italic" in base) or (font["post"].italicAngle != 0)
                weight = int(font["OS/2"].usWeightClass)
                var = 0
                wmin = wmax = weight
                if "fvar" in font:
                    for ax in font["fvar"].axes:
                        if ax.axisTag == "wght":
                            var = 1
                            wmin, wmax = int(ax.minValue), int(ax.maxValue)
                opts = subset.Options()
                opts.layout_features = ["*"]
                opts.name_IDs = [1, 2, 3, 4, 6]
                opts.hinting = False
                opts.notdef_outline = True
                opts.glyph_names = False
                opts.drop_tables += ["DSIG", "MVAR"] if False else ["DSIG"]
                sub = subset.Subsetter(opts)
                sub.populate(unicodes=UNICODES)
                sub.subset(font)
                outname = "%s_%s%s.ttf" % (fid, "i" if italic else "n", ("v" if var else str(weight)))
                # avoid collisions (e.g. two files with same style)
                k = 1
                while os.path.exists(os.path.join(OUT, outname)):
                    k += 1
                    outname = "%s_%s%s_%d.ttf" % (fid, "i" if italic else "n", ("v" if var else str(weight)), k)
                font.save(os.path.join(OUT, outname))
                files.append({"f": outname, "i": 1 if italic else 0, "v": var, "w": weight, "wmin": wmin, "wmax": wmax})
            except Exception as e:
                print("skip", base, e)
        if not files:
            continue
        name = meta_name(os.path.join(fdir, "METADATA.pb"), fid)
        families.append({"id": fid, "name": name, "category": FAM_CAT[fid], "files": files})
        for lf in ("OFL.txt", "LICENSE.txt"):
            lp = os.path.join(fdir, lf)
            if os.path.exists(lp):
                licenses.append("=" * 72 + "\n" + name + "  (" + lf + ")\n" + "=" * 72 + "\n" + open(lp, encoding="utf-8", errors="replace").read())
                break
    families.sort(key=lambda f: f["name"].lower())
    json.dump({"version": 1, "families": families}, open(os.path.join(OUT, "manifest.json"), "w"), separators=(",", ":"))
    open(os.path.join(OUT, "LICENSES.txt"), "w", encoding="utf-8").write(
        "KALAM bundles the following fonts, each under its own license (SIL Open Font License 1.1 or Apache 2.0).\n"
        "Fonts were obtained from the google/fonts repository and subset to Latin characters.\n\n" + "\n\n".join(licenses))
    total = sum(os.path.getsize(os.path.join(OUT, f)) for f in os.listdir(OUT))
    print("families:", len(families), "files:", len(os.listdir(OUT)), "bytes:", total)


main()
