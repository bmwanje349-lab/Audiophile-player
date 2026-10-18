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
    current_scene = game

    # Startup now loads imported assets in the background; wait for the staged
    # world builder rather than treating the loading screen as a blank scene.
    var wait_started_ms := Time.get_ticks_msec()
    while not bool(game.get("world_ready")):
        if Time.get_ticks_msec() - wait_started_ms > 120000:
            _fail("The game never finished building its 3D world.")
            return
        await process_frame

    # Allow active-camera selection and multiple render frames to run.
    for frame in range(8):
        await process_frame
    await RenderingServer.frame_post_draw

    var camera := game.get_node_or_null("FollowCamera") as Camera3D
    var car := game.get_node_or_null("PlayerSportsCar") as Node3D
    if camera == null:
        _fail("FollowCamera was not created.")
        return
    if camera.get_viewport().get_camera_3d() != camera:
        camera.make_current()
        await RenderingServer.frame_post_draw
    if camera.get_viewport().get_camera_3d() != camera:
        _fail("FollowCamera is not the active camera in the rendered viewport.")
        return
    if car == null or car.get_child_count() < 1:
        _fail("Player car node has no imported model child.")
        return
    var player_meshes := car.find_children("*", "MeshInstance3D", true, false)
    if player_meshes.is_empty():
        _fail("Player car contains no renderable mesh instances.")
        return
    if not ResourceLoader.exists("res://assets/vehicles/spectral_gt_rs_game_ready.glb"):
        _fail("Imported CC0 sports-car asset is missing from the build.")
        return
    if not ResourceLoader.exists("res://assets/textures/asphalt_track_diffuse.jpg"):
        _fail("Imported Poly Haven road texture is missing from the build.")
        return
    if not ResourceLoader.exists("res://assets/textures/leafy_grass_diffuse.jpg"):
        _fail("Imported Poly Haven leafy-ground texture is missing from the build.")
        return
    if not ResourceLoader.exists("res://assets/textures/autumn_field_puresky.hdr") and not ResourceLoader.exists("res://assets/textures/autumn_field_puresky.exr"):
        _fail("Imported Poly Haven pure-sky panorama is missing from the build.")
        return
    if not bool(game.get("hdri_sky_loaded")):
        _fail("The 3D environment is not using the imported CC0 panoramic sky.")
        return
    if game.get("detailed_car_scene") == null:
        _fail("Detailed GLB hero vehicle was not imported as a Godot scene.")
        return
    var traffic_car_scenes: Array = game.get("traffic_car_scenes")
    if traffic_car_scenes.size() < 3:
        _fail("Three imported traffic-car options were not loaded.")
        return
    var nature_trees: Array = game.get("nature_tree_scenes")
    if nature_trees.size() < 3:
        _fail("All three higher-detail CC0 roadside tree models were not loaded.")
        return

    var road_segments = game.get("road_segments")
    if road_segments == null or road_segments.size() < 10:
        _fail("Road generation produced too few segments.")
        return
    var first_road := road_segments[0] as Node3D
    if first_road.get_child_count() > 8:
        _fail("Road markings are not batched; too many separate road draw nodes remain.")
        return
    var road_batches := first_road.find_children("*", "MultiMeshInstance3D", false, false)
    if road_batches.size() < 4:
        _fail("Road marking MultiMesh batches were not created.")
        return
    if not bool(ProjectSettings.get_setting("physics/common/physics_interpolation", false)):
        _fail("Physics interpolation is disabled; transform motion may visibly judder.")
        return
    var road_texture_found := false
    for child in first_road.get_children():
        var mesh_instance := child as MeshInstance3D
        if mesh_instance != null and mesh_instance.material_override is StandardMaterial3D:
            var road_mat := mesh_instance.material_override as StandardMaterial3D
            if road_mat.albedo_texture != null:
                road_texture_found = true
                break
    if not road_texture_found:
        _fail("Road surface is not using the imported asphalt texture.")
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
    if left_button.action_mode != BaseButton.ACTION_MODE_BUTTON_PRESS or right_button.action_mode != BaseButton.ACTION_MODE_BUTTON_PRESS:
        _fail("Steering buttons must trigger on touch-down.")
        return

    # Verify that live spawns recycle prewarmed nodes rather than adding detailed
    # GLB cars, coin meshes, or materials to the scene tree while racing.
    var traffic_pool: Array = game.get("traffic_pool")
    var coin_pool: Array = game.get("coin_pool")
    if traffic_pool.size() < 6 or coin_pool.size() < 12:
        _fail("Traffic and coin pools were not fully prewarmed before gameplay.")
        return
    game.call("_restart")
    var child_count_before_spawn := game.get_child_count()
    game.call("_spawn_traffic")
    var spawned_traffic: Array = game.get("traffic")
    if spawned_traffic.size() != 1:
        _fail("Traffic spawn failed to activate a pooled car.")
        return
    var spawned_car := spawned_traffic[0]["node"] as Node3D
    if not traffic_pool.has(spawned_car) or not spawned_car.visible:
        _fail("Traffic spawn did not reuse and show a prewarmed car.")
        return
    game.call("_spawn_coin_train")
    var spawned_coins: Array = game.get("coin_rows")
    if spawned_coins.size() != 4:
        _fail("Coin train failed to activate four pooled coins.")
        return
    for coin_item in spawned_coins:
        var pooled_coin := coin_item["node"] as Node3D
        if not coin_pool.has(pooled_coin) or not pooled_coin.visible:
            _fail("Coin spawn did not reuse and show a prewarmed coin.")
            return
    if game.get_child_count() != child_count_before_spawn:
        _fail("Live traffic or coin spawns allocated new scene-tree nodes.")
        return
    game.call("_restart")

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

    # Regression: a target lane must not cause a crash while the car is physically elsewhere.
    var player := game.get_node_or_null("PlayerSportsCar") as Node3D
    var collision_probe := game.call("_make_car", Color(1.0, 0.2, 0.2), false) as Node3D
    if player == null or collision_probe == null:
        _fail("Could not create collision regression probe.")
        return
    game.add_child(collision_probe)
    game.set("player_lane", 1)
    player.position.x = -3.8
    collision_probe.position = Vector3(0.0, 0.0, player.position.z - 2.0)
    var traffic_items: Array = game.get("traffic")
    traffic_items.append({"node": collision_probe, "lane": 1})
    game.call("_update_traffic", 0.0)
    if bool(game.get("game_over")):
        _fail("Lane-based collision returned: separated car positions must not crash.")
        return

    # Regression: actual overlap must crash even when the target-lane index differs.
    traffic_items.clear()
    game.set("player_lane", 0)
    player.position.x = 0.0
    collision_probe.position = Vector3(0.0, 0.0, player.position.z - 3.1)
    traffic_items.append({"node": collision_probe, "lane": 1})
    game.call("_update_traffic", 0.0)
    if not bool(game.get("game_over")):
        _fail("Real-position collision failed when the vehicle bodies overlapped.")
        return
    game.call("_restart")

    # Confirm that the game loop progresses, rather than showing a static UI.
    await create_timer(1.25).timeout
    if int(game.get("score")) <= 0:
        _fail("Score did not advance after the game loop ran.")
        return

    # Capture after the renderer has finished a frame. Restrict the diversity
    # check to the central playfield, excluding HUD labels and touch buttons:
    # UI colors must never let a blank 3D world pass this test.
    await process_frame
    await RenderingServer.frame_post_draw
    var image := root.get_texture().get_image()
    if image == null or image.is_empty() or image.get_width() < 100 or image.get_height() < 100:
        _fail("Rendered viewport capture is empty or too small.")
        return

    # Save evidence before the visual assertion, including when the assertion fails.
    var workspace := OS.get_environment("GITHUB_WORKSPACE")
    if workspace.is_empty():
        workspace = "/tmp"
    var screenshot_path := workspace.path_join("turbo-trails-render-smoke.png")
    var save_error := image.save_png(screenshot_path)
    if save_error != OK:
        _fail("Failed to save render screenshot (error %d)." % save_error)
        return

    var bins: Dictionary = {}
    var world_left := int(image.get_width() * 0.12)
    var world_right := int(image.get_width() * 0.88)
    var world_top := int(image.get_height() * 0.25)
    var world_bottom := int(image.get_height() * 0.70)
    var step_x := maxi(1, int(image.get_width() / 96))
    var step_y := maxi(1, int(image.get_height() / 120))
    var world_region_min := image.get_pixel(world_left, world_top)
    var world_contrast_samples := 0
    for y in range(world_top, world_bottom, step_y):
        for x in range(world_left, world_right, step_x):
            var c := image.get_pixel(x, y)
            var key := "%02x%02x%02x" % [
                int(clampf(c.r, 0.0, 1.0) * 31.0),
                int(clampf(c.g, 0.0, 1.0) * 31.0),
                int(clampf(c.b, 0.0, 1.0) * 31.0)
            ]
            bins[key] = true
            if absf(c.r - world_region_min.r) + absf(c.g - world_region_min.g) + absf(c.b - world_region_min.b) > 0.14:
                world_contrast_samples += 1

    if bins.size() < 32 or world_contrast_samples < 80:
        _fail("Central 3D playfield is blank or visually flat (colour bins=%d, contrast samples=%d); HUD pixels are excluded." % [bins.size(), world_contrast_samples])
        return

    print("TURBO_TRAILS_SMOKE_PASS")
    print("camera=current; road_segments=%d; traffic_models=%d; imported_trees=%d; buttons=%d; score=%d; world_colour_bins=%d; world_contrast_samples=%d" % [
        road_segments.size(), traffic_car_scenes.size(), nature_trees.size(),
        buttons.size(), int(game.get("score")), bins.size(), world_contrast_samples
    ])
    print("render_screenshot=%s" % screenshot_path)
    quit(0)


func _fail(message: String) -> void:
    push_error("TURBO_TRAILS_SMOKE_FAIL: " + message)
    quit(1)
