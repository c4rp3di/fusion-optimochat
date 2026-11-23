"""Decodifica con OpenCV los PNG que genera PruebaQr y compara con el texto esperado (.txt junto a cada .png).
Prueba dos detectores y varias escalas: el decodificador de OpenCV falla a veces con imagenes validas."""
import sys, os, glob
try:
    import cv2
except Exception:
    print("SIN_OPENCV"); sys.exit(0)
detectores = [cv2.QRCodeDetector()]
if hasattr(cv2, "QRCodeDetectorAruco"):
    detectores.append(cv2.QRCodeDetectorAruco())
for f in sorted(glob.glob(os.path.join(sys.argv[1], "*.png"))):
    esperado = open(f[:-4] + ".txt", encoding="utf-8").read()
    img = cv2.imread(f)
    intentos = []
    for esc in (1.0, 0.5, 0.75, 1.5, 2.0):
        im = img if esc == 1.0 else cv2.resize(img, None, fx=esc, fy=esc, interpolation=cv2.INTER_NEAREST)
        for i, d in enumerate(detectores):
            try:
                txt, _, _ = d.detectAndDecode(im)
            except Exception:
                txt = ""
            if txt == esperado:
                intentos.append((i, esc))
    print(("OKQR " if intentos else "MALQR ") + os.path.basename(f) + " " + str(len(esperado.encode())) + " " + str(intentos[:3]))
