"""Print a muzika-nowplaying.json as the desktop would write it.
usage: handoff.py CURSOR [playing] [device_id]"""
import json, sys, time
T = [("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", 213),
     ("fJ9rUzIMcZQ", "Bohemian Rhapsody", "Queen", 359),
     ("9bZkp7q19f0", "Gangnam Style", "PSY", 252),
     ("kJQP7kiw5Fk", "Despacito", "Luis Fonsi", 282)]
cursor = int(sys.argv[1]); playing = len(sys.argv) > 2 and sys.argv[2] == "playing"
dev = sys.argv[3] if len(sys.argv) > 3 else "desktop-test"
queue = [dict(id=i, title=t, artist=a, duration=d, thumb=f"https://i.ytimg.com/vi/{i}/hqdefault.jpg",
              source="youtube", url=None, album=None, trackNumber=0) for i, t, a, d in T]
print(json.dumps(dict(format="muzika-nowplaying", version=1, device_id=dev, device="fedora-test",
    updated_at=time.time(), playing=playing, position_ms=int(__import__("os").environ.get("POS", "30000")), duration_ms=T[cursor][3]*1000,
    cursor=cursor, track_id=T[cursor][0], shuffle=False, repeat="none", queue=queue, order=[0,1,2,3])))
