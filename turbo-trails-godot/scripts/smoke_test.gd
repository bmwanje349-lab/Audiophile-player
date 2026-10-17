extends SceneTree

# Runtime smoke test for Turbo Trails 3D.
# This must run under a real (software) renderer, not --headless, so a blank
# viewport cannot pass merely because the project imports and exports.

func _initialize() -> void:
    call_deferred("_run_smoke_test")


func _run_smoke_test() -> void:
    var packed := load("res://scenes/main.tscn") as PackedScene
    if packed == null:
        _fail("Could not load the configured main scene.")
        return

    var game := packed.instantiate()
    root.add_child(game)

    # Allow _ready() and multiple render frames to run.
    for frame in range(8):
        await process_frame

    var camera := game.get_node_or_null("FollowCamera") as Camera3D
    var car := game.get_node_or_null("PlayerSportsCar") as Node3D
    if camera == null:
        _fail("FollowCamera was not created.")
        return
    if not camera.current:
        _fail("FollowCamera exists but is not the active camera.")
        return
    if car == null or car.get_child_count() < 12:
        _fail("Player car mesh assembly was not created.")
        return

    var road_segments = game.get("road_segments")
    if road_segments == null or road_segments.size() < 10:
        _fail("Road generation produced too few segments.")
        return

    var buttons := game.find_children("*", "Button", true, false)
    var left_button: Button
    var right_button: Button
    for node in buttons:
        var button := node as Button
        if button == null:
            continue
        if button.text == "◀":
            left_button = button
        elif button.text == "▶":
            right_button = button

    if left_button == null or right_button == null:
        _fail("Touch steering buttons are missing.")
        return

    var lane_before := int(game.get("player_lane"))
    left_button.pressed.emit()
    await process_frame
    if int(game.get("player_lane")) != maxi(0, lane_before - 1):
        _fail("Left steering button is not connected to lane movement.")
        return

    right_button.pressed.emit()
    await process_frame
    if int(game.get("player_lane")) != lane_before:
        _fail("Right steering button did not restore the original lane.")
        return

    # Confirm that the game loop progresses, rather than showing a static UI.
    await create_timer(1.25).timeout
    if int(game.get("score")) <= 0:
        _fail("Score did not advance after the game loop ran.")
        return

    # Capture an actual rendered frame. Pixel diversity catches empty/flat views.
    await process_frame
    await process_frame
    var image := root.get_texture().get_image()
    if image == null or image.is_empty() or image.get_width() < 100 or image.get_height() < 100:
        _fail("Rendered viewport capture is empty or too small.")
        return

    var bins: Dictionary = {}
    var step_x := maxi(1, int(image.get_width() / 72))
    var step_y := maxi(1, int(image.get_height() / 72))
    for y in range(0, image.get_height(), step_y):
        for x in range(0, image.get_width(), step_x):
            var c := image.get_pixel(x, y)
            var key := "%02x%02x%02x" % [
                int(clampf(c.r, 0.0, 1.0) * 31.0),
                int(clampf(c.g, 0.0, 1.0) * 31.0),
                int(clampf(c.b, 0.0, 1.0) * 31.0)
            ]
            bins[key] = true

    if bins.size() < 24:
        _fail("Rendered frame has too little pixel variation; likely blank or unrendered.")
        return

    var workspace := OS.get_environment("GITHUB_WORKSPACE")
    if workspace.is_empty():
        workspace = "/tmp"
    var screenshot_path := workspace.path_join("turbo-trails-render-smoke.png")
    var save_error := image.save_png(screenshot_path)
    if save_error != OK:
        _fail("Failed to save render screenshot (error %d)." % save_error)
        return

    print("TURBO_TRAILS_SMOKE_PASS")
    print("camera=current; road_segments=%d; buttons=%d; score=%d; colour_bins=%d" % [
        road_segments.size(), buttons.size(), int(game.get("score")), bins.size()
    ])
    print("render_screenshot=%s" % screenshot_path)
    quit(0)


func _fail(message: String) -> void:
    push_error("TURBO_TRAILS_SMOKE_FAIL: " + message)
    quit(1)
