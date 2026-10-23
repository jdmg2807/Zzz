#!/usr/bin/env python3
"""Genera `app/src/main/assets/vanilla/blocks.json` a partir de minecraft-data.

Datos: paquete npm **minecraft-data** (licencia MIT, PrismarineJS), fichero
`data/pc/<version>/blocks.json`, que publica las propiedades de los bloques de Minecraft
Java Edition (dureza, resistencia a explosiones, luz emitida, luz que deja pasar, apilado,
herramienta y nivel mínimo para que suelte el drop).

Formato de salida (compacto, una entrada por bloque):

    {"stone": [1.5, 6.0, 0, 15, 64, "pickaxe", 1, 0], ...}

Campos del array:
    0 dureza (segundos base; -1 = irrompible)
    1 resistencia a explosiones
    2 luz emitida (0..15)
    3 luz que deja pasar (0..15)
    4 tamaño de pila
    5 herramienta idónea ("pickaxe", "axe", "shovel", "hoe" o "")
    6 nivel mínimo de herramienta (0 = a mano, 1 = madera, 2 = piedra, 3 = hierro,
      4 = diamante, 5 = netherita)
    7 1 si el bloque es transparente (no oculta caras ni corta la luz)

Uso:
    npm pack minecraft-data && tar xzf minecraft-data-*.tgz
    python3 tools/gen_blocks.py package/minecraft-data/data/pc/1.21.9 .
"""

import json
import os
import sys

# Niveles de recolección en el orden en que aparece el primer pico que sirve.
TOOL_TIERS = {
    "wooden": 1,
    "golden": 1,
    "stone": 2,
    "copper": 2,
    "iron": 3,
    "diamond": 4,
    "netherite": 5,
}

TOOL_NAMES = ("pickaxe", "axe", "shovel", "hoe", "sword", "shears")


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    data_dir = sys.argv[1]
    out_root = sys.argv[2]

    with open(os.path.join(data_dir, "items.json"), encoding="utf-8") as f:
        items = {i["id"]: i["name"] for i in json.load(f)}
    with open(os.path.join(data_dir, "blocks.json"), encoding="utf-8") as f:
        blocks = json.load(f)

    def best_tool(harvest_tools):
        """(herramienta, nivel) mínimos a partir de la lista de herramientas válidas."""
        best_tool_name = ""
        best_tier = 0
        for tool_id in harvest_tools or []:
            name = items.get(int(tool_id))
            if not name:
                continue
            for family in TOOL_NAMES:
                if name.endswith("_" + family):
                    prefix = name[: -len(family) - 1]
                    tier = TOOL_TIERS.get(prefix, 0)
                    if not best_tool_name:
                        best_tool_name = family
                        best_tier = tier
                    elif tier < best_tier:
                        best_tier = tier
                    break
        return best_tool_name, best_tier

    def tool_of(material: str) -> str:
        # `material` es del tipo "mineable/pickaxe" o "leaves;mineable/hoe".
        for part in material.split(";"):
            part = part.strip()
            if part.startswith("mineable/"):
                return part.split("/", 1)[1]
        return ""

    out = {}
    for block in blocks:
        name = block.get("name")
        if not name or name == "air":
            continue
        hardness = float(block.get("hardness", 0.0))
        if block.get("diggable") is False and hardness == 0.0:
            hardness = -1.0
        tool = tool_of(block.get("material", "default"))
        harvest_tool, tier = best_tool(block.get("harvestTools"))
        if not tool:
            tool = harvest_tool
        out[name] = [
            round(hardness, 3),
            round(float(block.get("resistance", 0.0)), 3),
            int(block.get("emitLight", 0)),
            int(block.get("filterLight", 15)),
            int(block.get("stackSize", 64) or 64),
            tool,
            tier,
            0 if not block.get("transparent") else 1,
        ]

    target = os.path.join(out_root, "app", "src", "main", "assets", "vanilla", "blocks.json")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print("bloques escritos:", len(out), "->", target)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
