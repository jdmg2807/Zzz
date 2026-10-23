#!/usr/bin/env python3
"""Genera `app/src/main/assets/vanilla/recipes.json` a partir de minecraft-data.

Los datos vienen del paquete npm **minecraft-data** (licencia MIT, proyecto PrismarineJS),
que publica las recetas de Minecraft Java Edition en `data/pc/<version>/recipes.json`.
No se copia código del juego: solo la tabla de recetas (patrón y resultado), que es un
hecho del juego, y se guarda en un formato que el motor ya sabe leer (`registerRecipe`).

Uso:
    npm pack minecraft-data && tar xzf minecraft-data-*.tgz
    python3 tools/gen_recipes.py package/minecraft-data/data/pc/1.21.9 .

Salida: `assets/vanilla/recipes.json`, un array de recetas en formato vanilla:

    {"type": "minecraft:crafting_shaped", "pattern": ["AAA", "A A"],
     "key": {"A": "minecraft:oak_planks"}, "result": {"id": "minecraft:chest", "count": 1}}

Las etiquetas (tags) del juego llegan ya resueltas por minecraft-data a un único miembro
(por ejemplo `#planks` -> `acacia_planks`); el motor compensa eso con familias de materiales
(`sameIngredient`), así que una mesa de trabajo también se fabrica con abedul.
"""

import json
import os
import sys


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    data_dir = sys.argv[1]
    out_root = sys.argv[2]

    with open(os.path.join(data_dir, "items.json"), encoding="utf-8") as f:
        items = {i["id"]: i["name"] for i in json.load(f)}
    with open(os.path.join(data_dir, "recipes.json"), encoding="utf-8") as f:
        raw = json.load(f)

    def name(item_id):
        n = items.get(item_id)
        return None if n is None else "minecraft:" + n

    out = []
    seen = set()

    def add(recipe):
        key = json.dumps(recipe, sort_keys=True)
        if key in seen:
            return
        seen.add(key)
        out.append(recipe)

    for _rid, variants in raw.items():
        for variant in variants:
            result = variant.get("result") or {}
            result_id = name(result.get("id"))
            if not result_id:
                continue
            count = max(1, int(result.get("count", 1)))

            shape = variant.get("inShape")
            if shape:
                rows = [row for row in shape if row is not None]
                if not rows or not any(cell is not None for row in rows for cell in row):
                    continue
                # Quita filas/columnas vacías para que el patrón sea el mínimo posible.
                while rows and all(cell is None for cell in rows[0]):
                    rows.pop(0)
                while rows and all(cell is None for cell in rows[-1]):
                    rows.pop()
                if not rows:
                    continue
                width = max(len(row) for row in rows)
                cols = [c for c in range(width)
                        if any(r[c] is not None for r in rows if c < len(r))]
                if not cols:
                    continue
                first, last = cols[0], cols[-1]
                rows = [row[first:last + 1] for row in rows]

                letters = {}
                pattern = []
                ok = True
                for row in rows:
                    line = ""
                    for cell in row:
                        if cell is None:
                            line += " "
                            continue
                        if cell not in letters:
                            if len(letters) >= 26:
                                ok = False
                                break
                            letters[cell] = chr(ord("A") + len(letters))
                        line += letters[cell]
                    if not ok:
                        break
                    pattern.append(line)
                if not ok:
                    continue
                key = {}
                for cell, ch in letters.items():
                    n = name(cell)
                    if not n:
                        ok = False
                        break
                    key[ch] = n
                if not ok:
                    continue
                add({
                    "type": "minecraft:crafting_shaped",
                    "pattern": pattern,
                    "key": key,
                    "result": {"id": result_id, "count": count},
                })
                continue

            ingredients = variant.get("ingredients")
            if ingredients:
                ids = []
                ok = True
                for ing in ingredients:
                    if isinstance(ing, list):
                        ing = ing[0] if ing else None
                    n = name(ing)
                    if not n:
                        ok = False
                        break
                    ids.append(n)
                if not ok or not ids:
                    continue
                add({
                    "type": "minecraft:crafting_shapeless",
                    "ingredients": ids,
                    "result": {"id": result_id, "count": count},
                })

    target = os.path.join(out_root, "app", "src", "main", "assets", "vanilla", "recipes.json")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print("recetas escritas:", len(out), "->", target)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
