extends Node3D

# Turbo Trails 3D — Godot 4 arcade racer with imported CC0 vehicle and PBR road assets.

const SEGMENT_LENGTH: float = 36.0
const SEGMENT_COUNT: int = 14
const TRAFFIC_POOL_SIZE: int = 6
const COIN_POOL_SIZE: int = 12
const HUD_REFRESH_INTERVAL: float = 0.10

# Adaptive startup/recovery tiers from the blank-screen fix ZIP.
enum Tier { FULL, MEDIUM, LITE, SAFE }
const TIER_NAMES: Array[String] = ["FULL", "MEDIUM", "LITE", "SAFE"]
const BOOT_FLAG_PATH: String = "user://boot_in_progress.flag"
const QUALITY_CFG_PATH: String = "user://quality.cfg"
const ASSET_LOAD_TIMEOUT_MS: int = 45000
const ROAD_HALF_WIDTH: float = 6.0
const LANE_X: Array[float] = [-3.8, 0.0, 3.8]
const CAR_PAINTS: Array[Color] = [
    Color(1.0, 0.22, 0.30), Color(1.0, 0.55, 0.12),
    Color(0.57, 0.28, 1.0), Color(0.12, 0.76, 1.0),
    Color(1.0, 0.30, 0.68), Color(0.34, 0.86, 0.37)
]

var rng := RandomNumberGenerator.new()
var material_cache: Dictionary = {}
var box_mesh_cache: Dictionary = {}
var traffic_pool: Array[Node3D] = []
var coin_pool: Array[Node3D] = []
var coin_rim_mesh: CylinderMesh
var coin_face_mesh: CylinderMesh
var coin_glint_mesh: SphereMesh
var coin_rim_material: StandardMaterial3D
var coin_face_material: StandardMaterial3D
var coin_glint_material: StandardMaterial3D
var road_material_cache: StandardMaterial3D
var detailed_car_scene: PackedScene
var traffic_car_scenes: Array[PackedScene] = []
var nature_tree_scenes: Array[PackedScene] = []
var hdri_sky_loaded: bool = false
var grass_material_cache: StandardMaterial3D
var road_segments: Array[Node3D] = []
var traffic: Array[Dictionary] = []
var coin_rows: Array[Dictionary] = []
var scenery: Array[Node3D] = []
var player_car: Node3D
var camera: Camera3D
var game_over_panel: PanelContainer
var score_label: Label
var coins_label: Label
var over_score_label: Label
var steering_hint: Label

var player_lane: int = 1
var world_speed: float = 24.0
var distance_run: float = 0.0
var score: int = 0
var coins_collected: int = 0
var traffic_timer: float = 1.0
var coin_timer: float = 2.7
var game_over: bool = false
var camera_bump: float = 0.0
var hud_refresh_timer: float = 0.0

var tier: int = Tier.FULL
var is_mobile: bool = false
var world_ready: bool = false
var segment_count: int = SEGMENT_COUNT
var sphere_radial: int = 32
var sphere_rings: int = 20
var loaded_assets: Dictionary = {}
var loading_layer: CanvasLayer
var loading_label: Label
var diagnostics_label: Label
var ui_root: Control



func _ready() -> void:
    rng.randomize()
    is_mobile = OS.has_feature("android") or OS.has_feature("mobile") or OS.has_feature("web")
    # Allow recovery behaviour to be tested on desktop using -- --mobile.
    if "--mobile" in OS.get_cmdline_user_args():
        is_mobile = true
    _choose_tier()
    _apply_viewport_quality()

    # Put a real 2D loading screen on screen before doing any heavy work.
    _make_ui()
    _update_hud()
    _make_loading_overlay()
    await get_tree().process_frame

    _set_loading("Loading cars and scenery…")
    await _load_assets_async()

    _set_loading("Building sky and lights…")
    await get_tree().process_frame
    _make_environment()

    _set_loading("Building terrain…")
    await get_tree().process_frame
    _make_ground_and_hills()

    _set_loading("Laying the road…")
    await get_tree().process_frame
    _make_road()

    _set_loading("Planting trees…")
    await get_tree().process_frame
    _make_roadside_scenery()

    _set_loading("Starting your car…")
    await get_tree().process_frame
    player_car = _make_car(Color(0.03, 0.86, 0.78), true)
    player_car.position = Vector3(LANE_X[player_lane], 0.0, 3.0)
    add_child(player_car)

    _set_loading("Preparing traffic and collectibles…")
    await get_tree().process_frame
    await _prepare_runtime_pools()

    world_ready = true
    _update_hud()
    _build_diagnostics_text()
    await get_tree().process_frame
    if is_instance_valid(loading_layer):
        loading_layer.queue_free()
    _finish_boot_checks()


func _choose_tier() -> void:
    tier = Tier.FULL
    if not is_mobile:
        # Keep CI/editor rendering at full quality, but permit explicit tier tests.
        for arg in OS.get_cmdline_user_args():
            if arg.begins_with("--tier="):
                tier = clampi(int(arg.substr(7)), Tier.FULL, Tier.SAFE)
        _configure_tier_budget()
        return

    var base_tier: int = Tier.MEDIUM
    var stored: int = base_tier
    var cfg := ConfigFile.new()
    if cfg.load(QUALITY_CFG_PATH) == OK:
        stored = clampi(int(cfg.get_value("quality", "tier", base_tier)), base_tier, Tier.SAFE)
    # A flag left by an unclean exit signals that the previous startup may have
    # exhausted memory or stalled the graphics driver; retry with fewer resources.
    if FileAccess.file_exists(BOOT_FLAG_PATH):
        stored = mini(stored + 1, Tier.SAFE)
    tier = stored
    _save_tier()
    var flag := FileAccess.open(BOOT_FLAG_PATH, FileAccess.WRITE)
    if flag != null:
        flag.store_string(TIER_NAMES[tier])
        flag.close()
    _configure_tier_budget()


func _save_tier() -> void:
    var cfg := ConfigFile.new()
    cfg.set_value("quality", "tier", tier)
    cfg.save(QUALITY_CFG_PATH)


func _configure_tier_budget() -> void:
    match tier:
        Tier.FULL:
            segment_count = 14
            sphere_radial = 32
            sphere_rings = 20
        Tier.MEDIUM:
            segment_count = 14
            sphere_radial = 24
            sphere_rings = 14
        Tier.LITE:
            segment_count = 12
            sphere_radial = 16
            sphere_rings = 10
        _:
            segment_count = 10
            sphere_radial = 10
            sphere_rings = 6


func _apply_viewport_quality() -> void:
    var viewport := get_viewport()
    if viewport != null and tier >= Tier.MEDIUM:
        viewport.msaa_3d = Viewport.MSAA_DISABLED


func _asset(path: String) -> Resource:
    return loaded_assets.get(path, null) as Resource


func _wanted_asset_paths() -> Array[String]:
    var wanted: Array[String] = []
    # The SAFE tier uses procedural cars, road, ground, and sky only.
    if tier <= Tier.LITE:
        wanted.append("res://assets/vehicles/spectral_gt_rs_game_ready.glb")
        wanted.append("res://assets/vehicles/sedan_car.glb")
        wanted.append("res://assets/vehicles/hatchback_car.glb")
        wanted.append("res://assets/textures/asphalt_track_diffuse.jpg")
        wanted.append("res://assets/textures/asphalt_track_normal.jpg")
        wanted.append("res://assets/textures/asphalt_track_roughness.jpg")
        wanted.append("res://assets/textures/leafy_grass_diffuse.jpg")
        wanted.append("res://assets/textures/leafy_grass_roughness.jpg")
    if tier <= Tier.MEDIUM:
        wanted.append("res://assets/nature/mature_scots_pine_tree.glb")
        wanted.append("res://assets/nature/oak_detailed_leaves.glb")
        wanted.append("res://assets/nature/japanese_maple_tree.glb")
        wanted.append("res://assets/textures/autumn_field_puresky.hdr")
        wanted.append("res://assets/textures/autumn_field_puresky.exr")
    return wanted


func _load_assets_async() -> void:
    # Background resource requests keep the Godot main thread available for the
    # loading screen and Android lifecycle events.
    var pending: Array[String] = []
    for path in _wanted_asset_paths():
        if not ResourceLoader.exists(path):
            continue
        var request_result := ResourceLoader.load_threaded_request(path)
        if request_result == OK:
            pending.append(path)
        else:
            var immediate := load(path)
            if immediate != null:
                loaded_assets[path] = immediate

    var total: int = pending.size()
    var started_ms: int = Time.get_ticks_msec()
    while not pending.is_empty():
        for i in range(pending.size() - 1, -1, -1):
            var path: String = pending[i]
            var status := ResourceLoader.load_threaded_get_status(path)
            if status == ResourceLoader.THREAD_LOAD_IN_PROGRESS:
                continue
            if status == ResourceLoader.THREAD_LOAD_LOADED:
                var resource := ResourceLoader.load_threaded_get(path)
                if resource != null:
                    loaded_assets[path] = resource
            else:
                push_warning("Could not load %s; continuing with procedural fallback." % path)
            pending.remove_at(i)
        _set_loading("Loading cars and scenery…  %d / %d" % [total - pending.size(), total])
        if Time.get_ticks_msec() - started_ms > ASSET_LOAD_TIMEOUT_MS:
            push_warning("Asset loading timed out; continuing with assets that are ready.")
            break
        await get_tree().process_frame

    var hero := _asset("res://assets/vehicles/spectral_gt_rs_game_ready.glb") as PackedScene
    if hero != null:
        detailed_car_scene = hero
        traffic_car_scenes.append(hero)
    for vehicle_path in [
        "res://assets/vehicles/sedan_car.glb",
        "res://assets/vehicles/hatchback_car.glb"
    ]:
        var vehicle_scene := _asset(vehicle_path) as PackedScene
        if vehicle_scene != null:
            traffic_car_scenes.append(vehicle_scene)
    for tree_path in [
        "res://assets/nature/mature_scots_pine_tree.glb",
        "res://assets/nature/oak_detailed_leaves.glb",
        "res://assets/nature/japanese_maple_tree.glb"
    ]:
        var tree_scene := _asset(tree_path) as PackedScene
        if tree_scene != null:
            nature_tree_scenes.append(tree_scene)


func _prepare_runtime_pools() -> void:
    # Instantiate detailed imported models and coin meshes behind the loading
    # overlay, never in the middle of a race. Hidden pool entries are reused.
    for i in range(TRAFFIC_POOL_SIZE):
        var pooled_car := _make_car(CAR_PAINTS[i % CAR_PAINTS.size()], false)
        pooled_car.name = "PooledTrafficCar_%02d" % i
        pooled_car.position = Vector3(0.0, -1000.0, 0.0)
        pooled_car.visible = false
        pooled_car.process_mode = Node.PROCESS_MODE_DISABLED
        add_child(pooled_car)
        traffic_pool.append(pooled_car)
        if i % 2 == 1:
            _set_loading("Preparing traffic…  %d / %d" % [i + 1, TRAFFIC_POOL_SIZE])
            await get_tree().process_frame

    for i in range(COIN_POOL_SIZE):
        var pooled_coin := _make_coin()
        pooled_coin.name = "PooledCoin_%02d" % i
        pooled_coin.position = Vector3(0.0, -1000.0, 0.0)
        pooled_coin.visible = false
        pooled_coin.process_mode = Node.PROCESS_MODE_DISABLED
        add_child(pooled_coin)
        coin_pool.append(pooled_coin)
        if i % 4 == 3:
            _set_loading("Preparing collectibles…  %d / %d" % [i + 1, COIN_POOL_SIZE])
            await get_tree().process_frame


func _make_loading_overlay() -> void:
    loading_layer = CanvasLayer.new()
    loading_layer.layer = 20
    add_child(loading_layer)
    var backdrop := ColorRect.new()
    backdrop.color = Color(0.04, 0.12, 0.22, 1.0)
    backdrop.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    backdrop.mouse_filter = Control.MOUSE_FILTER_IGNORE
    loading_layer.add_child(backdrop)
    var title := Label.new()
    title.text = "T U R B O   T R A I L S   3D"
    title.anchor_left = 0.0
    title.anchor_right = 1.0
    title.anchor_top = 0.36
    title.anchor_bottom = 0.46
    title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    title.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    title.add_theme_font_size_override("font_size", 30)
    title.add_theme_color_override("font_color", Color(1.0, 0.85, 0.27))
    backdrop.add_child(title)
    loading_label = Label.new()
    loading_label.text = "Loading…"
    loading_label.anchor_left = 0.0
    loading_label.anchor_right = 1.0
    loading_label.anchor_top = 0.48
    loading_label.anchor_bottom = 0.56
    loading_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    loading_label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    loading_label.add_theme_font_size_override("font_size", 18)
    loading_label.add_theme_color_override("font_color", Color(1.0, 1.0, 1.0, 0.92))
    backdrop.add_child(loading_label)
    var tier_label := Label.new()
    tier_label.text = "graphics: %s" % TIER_NAMES[tier]
    tier_label.anchor_left = 0.0
    tier_label.anchor_right = 1.0
    tier_label.anchor_top = 0.92
    tier_label.anchor_bottom = 0.97
    tier_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    tier_label.add_theme_font_size_override("font_size", 12)
    tier_label.add_theme_color_override("font_color", Color(1.0, 1.0, 1.0, 0.55))
    backdrop.add_child(tier_label)


func _set_loading(text_value: String) -> void:
    if is_instance_valid(loading_label):
        loading_label.text = text_value


func _build_diagnostics_text() -> void:
    if not is_instance_valid(ui_root):
        return
    diagnostics_label = Label.new()
    diagnostics_label.text = "graphics %s · %s · %s · trees %d · cars %d" % [
        TIER_NAMES[tier],
        RenderingServer.get_current_rendering_method(),
        RenderingServer.get_video_adapter_name(),
        scenery.size(), traffic_car_scenes.size()
    ]
    diagnostics_label.anchor_left = 0.03
    diagnostics_label.anchor_right = 0.97
    diagnostics_label.anchor_top = 0.865
    diagnostics_label.anchor_bottom = 0.895
    diagnostics_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    diagnostics_label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    diagnostics_label.add_theme_font_size_override("font_size", 11)
    diagnostics_label.add_theme_color_override("font_color", Color(1.0, 1.0, 1.0, 0.85))
    diagnostics_label.add_theme_color_override("font_shadow_color", Color(0.0, 0.0, 0.0, 0.9))
    diagnostics_label.add_theme_constant_override("shadow_offset_x", 1)
    diagnostics_label.add_theme_constant_override("shadow_offset_y", 1)
    ui_root.add_child(diagnostics_label)


func _finish_boot_checks() -> void:
    if not is_mobile:
        return
    await get_tree().create_timer(1.6).timeout
    await RenderingServer.frame_post_draw
    var blank := _viewport_looks_blank()
    if blank and tier < Tier.SAFE:
        push_warning("3D view looked blank at tier %s; stepping down and restarting." % TIER_NAMES[tier])
        tier += 1
        _save_tier()
        _clear_boot_flag()
        get_tree().reload_current_scene()
        return
    if blank:
        push_warning("Viewport still looks blank at SAFE tier; applying flat-render fallback.")
        _activate_flat_render_fallback()
        await get_tree().process_frame
        await RenderingServer.frame_post_draw
        if _viewport_looks_blank():
            _show_render_recovery_notice()
    _clear_boot_flag()
    await get_tree().create_timer(10.0).timeout
    if is_instance_valid(diagnostics_label):
        diagnostics_label.queue_free()


func _activate_flat_render_fallback() -> void:
    if is_instance_valid(camera):
        camera.make_current()
        camera.near = 0.05
        camera.far = 500.0
    for node in find_children("*", "WorldEnvironment", true, false):
        var world_env := node as WorldEnvironment
        if world_env == null or world_env.environment == null:
            continue
        var env := world_env.environment
        env.background_mode = Environment.BG_COLOR
        env.background_color = Color(0.38, 0.72, 0.94)
        env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
        env.ambient_light_color = Color(0.85, 0.88, 0.92)
        env.ambient_light_energy = 1.0
        env.fog_enabled = false
    for node in find_children("*", "DirectionalLight3D", true, false):
        var light := node as DirectionalLight3D
        if light != null:
            light.shadow_enabled = false
    RenderingServer.set_default_clear_color(Color(0.38, 0.72, 0.94))


func _show_render_recovery_notice() -> void:
    var layer := CanvasLayer.new()
    layer.layer = 100
    add_child(layer)
    var panel := ColorRect.new()
    panel.color = Color(0.025, 0.07, 0.12, 0.96)
    panel.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    layer.add_child(panel)
    var label := Label.new()
    label.text = "TURBO TRAILS\n\nYour device's 3D renderer did not return a usable frame.\nClose and reopen the game. If this repeats, use the Compatibility renderer build."
    label.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    label.offset_left = 24.0
    label.offset_right = -24.0
    label.offset_top = 24.0
    label.offset_bottom = -24.0
    label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
    label.add_theme_font_size_override("font_size", 22)
    label.add_theme_color_override("font_color", Color.WHITE)
    panel.add_child(label)


func _clear_boot_flag() -> void:
    if FileAccess.file_exists(BOOT_FLAG_PATH):
        DirAccess.remove_absolute(ProjectSettings.globalize_path(BOOT_FLAG_PATH))


func _viewport_looks_blank() -> bool:
    # Keep the simulation hook for CI/recovery tests. A synchronous GPU readback
    # (get_texture().get_image()) can stall the render thread for a visible hitch
    # on mobile, so normal gameplay checks scene/camera readiness instead.
    if "--simulate-blank" in OS.get_cmdline_user_args():
        return true
    var viewport := get_viewport()
    if viewport == null or not is_instance_valid(camera):
        return true
    if viewport.get_camera_3d() != camera:
        return true
    if road_segments.is_empty() or not is_instance_valid(player_car):
        return true
    return player_car.find_children("*", "MeshInstance3D", true, false).is_empty()

func _physics_process(delta: float) -> void:
    # Resume/background frame gaps must not teleport traffic or scenery across the player.
    delta = minf(delta, 0.05)

    # _ready() deliberately yields while asset loading and world construction run.
    # Do not run movement or camera code until those nodes actually exist.
    if not world_ready:
        return

    # Some Android surface/activity transitions can reset the active camera.
    # Reassert it so the scene cannot silently render without a view camera.
    if is_instance_valid(camera):
        var active_viewport := camera.get_viewport()
        # "current" alone can be stale after Android surface/activity recreation;
        # verify the actual viewport camera to prevent a silently blank 3D world.
        if active_viewport != null and active_viewport.get_camera_3d() != camera:
            camera.make_current()

    if game_over:
        # Keep the impact shake active during the game-over screen instead of returning
        # before the camera transform is updated.
        if is_instance_valid(camera):
            var shake_phase: float = float(Time.get_ticks_msec()) * 0.055
            camera.position.x = player_car.position.x * 0.20 + sin(shake_phase) * camera_bump * 0.35
            camera.position.y = 6.2 + camera_bump + cos(shake_phase * 1.37) * camera_bump * 0.18
            camera.look_at(Vector3(player_car.position.x * 0.12, 0.65, -25.0), Vector3.UP)
        camera_bump = move_toward(camera_bump, 0.0, delta * 0.85)
        return

    distance_run += world_speed * delta
    score = int(distance_run * 2.0) + coins_collected * 100
    world_speed = minf(36.0, 24.0 + distance_run * 0.012)

    for segment in road_segments:
        segment.position.z += world_speed * delta
        if segment.position.z > SEGMENT_LENGTH * 0.72:
            segment.position.z -= SEGMENT_LENGTH * segment_count
            segment.reset_physics_interpolation()

    for prop in scenery:
        prop.position.z += world_speed * delta
        if prop.position.z > 28.0:
            var side: float = -1.0 if prop.position.x < 0.0 else 1.0
            prop.position.z = -160.0 - rng.randf_range(0.0, 45.0)
            prop.position.x = side * rng.randf_range(8.0, 14.0)
            prop.reset_physics_interpolation()

    player_car.position.x = move_toward(
        player_car.position.x, LANE_X[player_lane], delta * 14.0
    )
    var steer_offset: float = LANE_X[player_lane] - player_car.position.x
    player_car.rotation.z = clampf(-steer_offset * 0.045, -0.10, 0.10)
    camera.position.x = lerpf(camera.position.x, player_car.position.x * 0.20, delta * 2.0)
    camera.position.y = 6.2 + sin(distance_run * 0.035) * 0.055 + camera_bump
    camera.look_at(Vector3(player_car.position.x * 0.12, 0.9, -10.0), Vector3.UP)

    traffic_timer -= delta
    if traffic_timer <= 0.0:
        _spawn_traffic()
        traffic_timer = maxf(1.05, 1.75 - distance_run * 0.00010) + rng.randf_range(0.0, 0.28)

    coin_timer -= delta
    if coin_timer <= 0.0:
        _spawn_coin_train()
        coin_timer = rng.randf_range(3.3, 4.8)

    _update_traffic(delta)
    _update_coins(delta)
    hud_refresh_timer += delta
    if hud_refresh_timer >= HUD_REFRESH_INTERVAL:
        hud_refresh_timer = fmod(hud_refresh_timer, HUD_REFRESH_INTERVAL)
        _update_hud()


func _make_environment() -> void:
    var env := Environment.new()
    env.background_mode = Environment.BG_SKY
    env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
    env.ambient_light_energy = 0.62
    env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
    env.tonemap_exposure = 0.90
    env.fog_enabled = true
    env.fog_light_color = Color(0.66, 0.77, 0.82)
    env.fog_density = 0.0012

    var sky := Sky.new()
    var hdri_texture: Texture2D
    for sky_path in [
        "res://assets/textures/autumn_field_puresky.hdr",
        "res://assets/textures/autumn_field_puresky.exr"
    ]:
        var cached_sky := _asset(sky_path) as Texture2D
        if cached_sky != null:
            hdri_texture = cached_sky
            break

    if hdri_texture != null:
        var panorama_material := PanoramaSkyMaterial.new()
        panorama_material.panorama = hdri_texture
        panorama_material.energy_multiplier = 0.78
        sky.sky_material = panorama_material
        hdri_sky_loaded = true
    else:
        # Keep a safe editor fallback, while CI smoke tests require the real asset.
        var sky_material := ProceduralSkyMaterial.new()
        sky_material.sky_top_color = Color(0.08, 0.55, 0.96)
        sky_material.sky_horizon_color = Color(0.63, 0.88, 1.0)
        sky_material.ground_bottom_color = Color(0.25, 0.56, 0.31)
        sky_material.ground_horizon_color = Color(0.70, 0.91, 0.76)
        sky.sky_material = sky_material
    env.sky = sky

    var world_environment := WorldEnvironment.new()
    world_environment.environment = env
    add_child(world_environment)

    var sun := DirectionalLight3D.new()
    sun.name = "Sunlight"
    sun.rotation_degrees = Vector3(-48.0, -28.0, 0.0)
    sun.light_color = Color(1.0, 0.91, 0.73)
    sun.light_energy = 0.88
    sun.shadow_enabled = tier < Tier.SAFE
    # One shadow split is cheaper than the multi-split default on mobile GPUs.
    sun.directional_shadow_mode = DirectionalLight3D.SHADOW_ORTHOGONAL
    sun.directional_shadow_max_distance = 65.0
    add_child(sun)

    camera = Camera3D.new()
    camera.name = "FollowCamera"
    camera.position = Vector3(0.0, 6.2, 13.0)
    camera.fov = 62.0
    add_child(camera)
    camera.look_at(Vector3(0.0, 0.65, -25.0), Vector3.UP)
    # Make the camera current only after it belongs to the active scene tree.
    camera.make_current()


func _make_ground_and_hills() -> void:
    var ground_mesh := PlaneMesh.new()
    ground_mesh.size = Vector2(260.0, 620.0)
    var ground := MeshInstance3D.new()
    ground.name = "EndlessGreenGround"
    ground.mesh = ground_mesh
    ground.position = Vector3(0.0, -0.34, -120.0)
    ground.material_override = _get_grass_material()
    add_child(ground)

    _make_terrain_banks()

    # The CC0 panorama already supplies a natural sky; procedural cloud blobs
    # are only retained when running an editor preview without downloaded assets.
    if hdri_sky_loaded:
        return

    for i in range(8):
        var cloud_root := Node3D.new()
        cloud_root.name = "DistantCloud"
        cloud_root.position = Vector3(
            (-1.0 if i % 2 == 0 else 1.0) * rng.randf_range(22.0, 55.0),
            rng.randf_range(15.0, 28.0),
            -100.0 - rng.randf_range(0.0, 90.0)
        )
        var cloud_color := Color(0.95, 0.99, 1.0)
        _add_sphere(cloud_root, Vector3(5.0, 1.6, 2.4), Vector3.ZERO, cloud_color)
        _add_sphere(cloud_root, Vector3(3.5, 1.9, 2.0), Vector3(-3.2, -0.1, 0.0), cloud_color)
        _add_sphere(cloud_root, Vector3(3.8, 1.5, 2.1), Vector3(3.0, -0.25, 0.3), cloud_color)
        add_child(cloud_root)



func _make_terrain_banks() -> void:
    # Two smooth terrain meshes replace the giant sphere hills. A seeded, low-
    # frequency noise field makes natural rolling banks while leaving the road
    # shoulder and tree roots level. The shared grass PBR material keeps this
    # detail inexpensive on mobile GPUs.
    var terrain_noise := FastNoiseLite.new()
    terrain_noise.seed = rng.randi()
    terrain_noise.frequency = 0.016
    terrain_noise.fractal_octaves = 4
    terrain_noise.fractal_gain = 0.48
    _make_terrain_bank(-1.0, terrain_noise)
    _make_terrain_bank(1.0, terrain_noise)


func _make_terrain_bank(side: float, terrain_noise: FastNoiseLite) -> void:
    var x_segments: int = 36
    var z_segments: int = 100
    if tier == Tier.LITE:
        x_segments = 24
        z_segments = 70
    elif tier == Tier.SAFE:
        x_segments = 12
        z_segments = 35
    var vertices := PackedVector3Array()
    var uvs := PackedVector2Array()
    var indices := PackedInt32Array()

    for zi in range(z_segments + 1):
        var z_amount: float = float(zi) / float(z_segments)
        var z: float = lerpf(24.0, -360.0, z_amount)
        for xi in range(x_segments + 1):
            var x_amount: float = float(xi) / float(x_segments)
            var x_abs: float = lerpf(6.42, 92.0, x_amount)
            var rise: float = smoothstep(14.0, 29.0, x_abs)
            var outer_fade: float = 1.0 - smoothstep(76.0, 92.0, x_abs)
            var ridge_amount: float = clampf((x_abs - 10.0) / 82.0, 0.0, 1.0)
            var ridge_shape: float = sin(ridge_amount * PI)
            var noise_height: float = terrain_noise.get_noise_2d(side * x_abs, z) * 1.2
            var height: float = rise * outer_fade * maxf(0.0, ridge_shape * 5.6 + noise_height * 1.25)
            vertices.append(Vector3(side * x_abs, -0.32 + height, z))
            # World-space scaling gives the same natural grass tile size on both banks.
            uvs.append(Vector2(x_abs / 8.0, z / 8.0))

    for zi in range(z_segments):
        for xi in range(x_segments):
            var a: int = zi * (x_segments + 1) + xi
            var b: int = a + 1
            var c: int = a + x_segments + 1
            var d: int = c + 1
            if side > 0.0:
                indices.append_array(PackedInt32Array([a, b, c, b, d, c]))
            else:
                indices.append_array(PackedInt32Array([a, c, b, b, c, d]))

    var surface := SurfaceTool.new()
    surface.begin(Mesh.PRIMITIVE_TRIANGLES)
    for i in range(vertices.size()):
        surface.set_uv(uvs[i])
        surface.add_vertex(vertices[i])
    for index_value in indices:
        surface.add_index(index_value)
    surface.generate_normals()
    var terrain_mesh := surface.commit()
    if terrain_mesh == null:
        push_warning("Could not create rolling terrain mesh; retaining the flat PBR ground.")
        return

    var terrain := MeshInstance3D.new()
    terrain.name = "RollingTerrainBank_Left" if side < 0.0 else "RollingTerrainBank_Right"
    terrain.mesh = terrain_mesh
    terrain.material_override = _get_grass_material()
    terrain.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
    add_child(terrain)


func _make_road() -> void:
    # Draw repeated road paint/curbs through MultiMesh batches. The geometry,
    # materials, spacing and colours stay the same, but hundreds of individual
    # MeshInstance draw calls collapse to five batches per road segment.
    var edge_material := _mat(Color(1.0, 0.82, 0.35), 0.7)
    var lane_material := _mat(Color(0.96, 0.97, 0.89), 0.75)
    var red_curb_material := _mat(Color(1.0, 0.36, 0.31), 0.7)
    var white_curb_material := _mat(Color(1.0, 0.95, 0.81), 0.7)

    for i in range(segment_count):
        var segment := Node3D.new()
        segment.name = "RoadSegment_%02d" % i
        segment.position.z = 18.0 - float(i) * SEGMENT_LENGTH
        _add_box(segment, Vector3(12.0, 0.22, SEGMENT_LENGTH), Vector3(0.0, -0.15, 0.0), _get_road_material(), false)

        var edge_transforms: Array[Transform3D] = []
        for edge_x in [-5.86, 5.86]:
            edge_transforms.append(Transform3D(Basis.IDENTITY, Vector3(edge_x, -0.025, 0.0)))
        _add_multimesh_boxes(segment, Vector3(0.24, 0.035, SEGMENT_LENGTH), edge_transforms, edge_material)

        var divider_transforms: Array[Transform3D] = []
        for dash_index in range(6):
            var dash_z: float = -SEGMENT_LENGTH * 0.42 + float(dash_index) * 6.0
            for divider_x in [-1.9, 1.9]:
                divider_transforms.append(Transform3D(Basis.IDENTITY, Vector3(divider_x, -0.025, dash_z)))
        _add_multimesh_boxes(segment, Vector3(0.105, 0.035, 2.8), divider_transforms, lane_material)

        var red_curb_transforms: Array[Transform3D] = []
        var white_curb_transforms: Array[Transform3D] = []
        for curb_index in range(6):
            var curb_z: float = -SEGMENT_LENGTH * 0.42 + float(curb_index) * 6.0
            for curb_x in [-6.08, 6.08]:
                var curb_transform := Transform3D(Basis.IDENTITY, Vector3(curb_x, -0.02, curb_z))
                if curb_index % 2 == 0:
                    red_curb_transforms.append(curb_transform)
                else:
                    white_curb_transforms.append(curb_transform)

        var curb_size := Vector3(0.28, 0.13, 2.7)
        _add_multimesh_boxes(segment, curb_size, red_curb_transforms, red_curb_material)
        _add_multimesh_boxes(segment, curb_size, white_curb_transforms, white_curb_material)

        add_child(segment)
        road_segments.append(segment)


func _add_multimesh_boxes(parent: Node3D, size: Vector3, transforms: Array[Transform3D], material_value: Material) -> MultiMeshInstance3D:
    if transforms.is_empty():
        return null
    var multimesh := MultiMesh.new()
    multimesh.transform_format = MultiMesh.TRANSFORM_3D
    multimesh.mesh = _get_box_mesh(size)
    multimesh.instance_count = transforms.size()
    for i in range(transforms.size()):
        multimesh.set_instance_transform(i, transforms[i])
    # Explicit bounds include the full segment so batched markings remain visible
    # while the road recycles past the camera.
    multimesh.custom_aabb = AABB(Vector3(-7.0, -1.0, -19.0), Vector3(14.0, 4.0, 38.0))
    var instance := MultiMeshInstance3D.new()
    instance.multimesh = multimesh
    instance.material_override = material_value
    instance.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
    parent.add_child(instance)
    return instance

func _make_roadside_scenery() -> void:
    # Use fewer high-detail tree instances to preserve frame time on mobile.
    var tree_count: int = 26
    if tier == Tier.LITE:
        tree_count = 16
    elif tier == Tier.SAFE:
        tree_count = 8
    for i in range(tree_count):
        var side: float = -1.0 if i % 2 == 0 else 1.0
        var prop := _make_tree(
            Vector3(
                side * rng.randf_range(8.2, 13.5),
                0.0,
                -float(i) * 10.7 - rng.randf_range(0.0, 9.0)
            ),
            i
        )
        add_child(prop)
        scenery.append(prop)


func _make_tree(world_position: Vector3, style_index: int) -> Node3D:
    var tree := Node3D.new()
    tree.name = "ImportedRoadsideTree" if not nature_tree_scenes.is_empty() else "ColourfulRoadsideTree"
    tree.position = world_position

    if not nature_tree_scenes.is_empty():
        var tree_index: int = posmod(style_index, nature_tree_scenes.size())
        var selected_scene: PackedScene = nature_tree_scenes[tree_index]
        var imported_tree := selected_scene.instantiate() as Node3D
        if imported_tree != null:
            # Models are authored at real-world dimensions: pines are ~18 m tall,
            # while the deciduous street trees are ~9 m tall.
            var tree_scale: float
            if tree_index == 0:
                tree_scale = rng.randf_range(0.50, 0.60)
            elif tree_index == 2:
                tree_scale = rng.randf_range(0.98, 1.18)
            else:
                tree_scale = rng.randf_range(0.76, 0.92)
            imported_tree.scale = Vector3.ONE * tree_scale
            imported_tree.rotation.y = rng.randf_range(-PI, PI)
            tree.add_child(imported_tree)
            return tree

    var trunk_height: float = rng.randf_range(1.2, 2.0)
    var trunk := CylinderMesh.new()
    trunk.top_radius = 0.24
    trunk.bottom_radius = 0.36
    trunk.height = trunk_height
    trunk.radial_segments = 8
    var trunk_node := MeshInstance3D.new()
    trunk_node.mesh = trunk
    trunk_node.position.y = trunk_height * 0.5
    trunk_node.material_override = _mat(Color(0.43, 0.25, 0.12), 0.9)
    trunk_node.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
    tree.add_child(trunk_node)

    var leaves: Array[Color] = [
        Color(0.09, 0.75, 0.35), Color(0.22, 0.83, 0.30),
        Color(0.98, 0.38, 0.53), Color(0.99, 0.62, 0.22),
        Color(0.53, 0.37, 0.98), Color(0.08, 0.76, 0.70)
    ]
    var crown_color: Color = leaves[style_index % leaves.size()]
    _add_sphere(tree, Vector3(1.25, 1.45, 1.15), Vector3(0.0, trunk_height + 0.9, 0.0), crown_color)
    _add_sphere(tree, Vector3(0.86, 1.05, 0.88), Vector3(-0.67, trunk_height + 0.55, 0.03), crown_color.lightened(0.08))
    _add_sphere(tree, Vector3(0.88, 1.03, 0.90), Vector3(0.64, trunk_height + 0.52, -0.12), crown_color.darkened(0.04))
    _add_sphere(tree, Vector3(0.15, 0.15, 0.15), Vector3(0.52, trunk_height + 1.1, -0.7), Color(1.0, 0.90, 0.28))
    return tree


func _make_car(paint: Color, is_player: bool) -> Node3D:
    # The player uses the hero sports car. Traffic cycles through three imported
    # CC0 vehicles, so the roadway does not look like cloned copies of one model.
    var selected_scene: PackedScene = detailed_car_scene if is_player else null
    if not is_player and not traffic_car_scenes.is_empty():
        selected_scene = traffic_car_scenes[rng.randi_range(0, traffic_car_scenes.size() - 1)]
    if selected_scene != null:
        var imported_root := Node3D.new()
        imported_root.name = "PlayerSportsCar" if is_player else "TrafficCar"
        var imported_car := selected_scene.instantiate() as Node3D
        if imported_car != null:
            imported_car.name = "SpectralGT_RS" if selected_scene == detailed_car_scene else "ImportedTrafficVehicle"
            # The downloaded models use +X as their longitudinal axis. The player's
            # front points down -Z; oncoming traffic must point toward +Z, not drive
            # backwards into the player.
            imported_car.rotation.y = PI * 0.5 if is_player else -PI * 0.5
            imported_car.scale = Vector3.ONE * 0.72
            imported_root.add_child(imported_car)
            return imported_root

    var car := Node3D.new()
    car.name = "PlayerSportsCar" if is_player else "TrafficCar"
    # Procedural fallback faces down -Z, so reverse oncoming traffic as well.
    car.rotation.y = 0.0 if is_player else PI
    var body_material := _mat(paint, 0.24, 0.18)
    var dark_material := _mat(Color(0.035, 0.055, 0.085), 0.55)
    var glass_material := _mat(Color(0.18, 0.63, 0.83), 0.18, 0.16)
    var chrome_material := _mat(Color(0.72, 0.85, 0.91), 0.2, 0.75)
    var tire_material := _mat(Color(0.035, 0.04, 0.06), 0.94)
    var headlight_material := _mat(Color(1.0, 0.94, 0.62), 0.18, 0.05, Color(1.0, 0.8, 0.24))
    var tail_material := _mat(Color(1.0, 0.06, 0.09), 0.25, 0.0, Color(1.0, 0.025, 0.02))

    _add_box(car, Vector3(1.82, 0.49, 3.25), Vector3(0.0, 0.56, 0.0), body_material)
    _add_box(car, Vector3(1.68, 0.22, 1.12), Vector3(0.0, 0.75, -1.05), body_material)
    _add_box(car, Vector3(1.66, 0.20, 0.82), Vector3(0.0, 0.68, 1.13), body_material)
    _add_box(car, Vector3(1.22, 0.57, 1.47), Vector3(0.0, 0.98, 0.12), glass_material)
    _add_box(car, Vector3(1.31, 0.12, 1.55), Vector3(0.0, 1.29, 0.12), body_material)
    _add_box(car, Vector3(1.48, 0.11, 0.30), Vector3(0.0, 0.98, -0.72), body_material)
    _add_box(car, Vector3(1.68, 0.16, 0.20), Vector3(0.0, 0.36, -1.66), dark_material)
    _add_box(car, Vector3(0.64, 0.105, 0.06), Vector3(0.0, 0.48, -1.78), chrome_material)
    _add_box(car, Vector3(0.40, 0.13, 0.075), Vector3(-0.59, 0.76, -1.66), headlight_material)
    _add_box(car, Vector3(0.40, 0.13, 0.075), Vector3(0.59, 0.76, -1.66), headlight_material)
    _add_box(car, Vector3(0.43, 0.13, 0.08), Vector3(-0.56, 0.70, 1.67), tail_material)
    _add_box(car, Vector3(0.43, 0.13, 0.08), Vector3(0.56, 0.70, 1.67), tail_material)

    for wheel_x in [-0.91, 0.91]:
        for wheel_z in [-1.02, 1.05]:
            var wheel_mesh := CylinderMesh.new()
            wheel_mesh.top_radius = 0.36
            wheel_mesh.bottom_radius = 0.36
            wheel_mesh.height = 0.24
            wheel_mesh.radial_segments = 12
            var wheel := MeshInstance3D.new()
            wheel.mesh = wheel_mesh
            wheel.rotation.z = PI * 0.5
            wheel.position = Vector3(wheel_x, 0.36, wheel_z)
            wheel.material_override = tire_material
            car.add_child(wheel)
            var hub := CylinderMesh.new()
            hub.top_radius = 0.20
            hub.bottom_radius = 0.20
            hub.height = 0.255
            hub.radial_segments = 10
            var hub_node := MeshInstance3D.new()
            hub_node.mesh = hub
            hub_node.rotation.z = PI * 0.5
            hub_node.position = Vector3(wheel_x * 1.08, 0.36, wheel_z)
            hub_node.material_override = chrome_material
            car.add_child(hub_node)

    _add_box(car, Vector3(0.10, 0.025, 0.76), Vector3(0.0, 1.355, -0.04), chrome_material)
    for mirror_x in [-0.78, 0.78]:
        _add_box(car, Vector3(0.22, 0.12, 0.24), Vector3(mirror_x, 0.93, -0.36), body_material)
    if is_player:
        _add_box(car, Vector3(0.12, 0.025, 1.04), Vector3(0.0, 0.875, -1.03), chrome_material)
        _add_box(car, Vector3(1.55, 0.08, 0.08), Vector3(0.0, 0.84, 1.68), dark_material)
        _add_box(car, Vector3(0.11, 0.025, 0.7), Vector3(-0.40, 0.878, 0.06), chrome_material)
    return car


func _spawn_traffic() -> void:
    # Reuse a prebuilt imported car. Instantiating a GLB during the live race can
    # block the main thread when its meshes/materials are attached to the scene.
    var car: Node3D
    for pooled_car in traffic_pool:
        if is_instance_valid(pooled_car) and not pooled_car.visible:
            car = pooled_car
            break
    if car == null:
        # The pool is sized for the maximum cars normally on-screen. If a device
        # has an unusually long frame, defer a spawn instead of allocating a new car.
        traffic_timer = 0.18
        return

    var lane: int = rng.randi_range(0, 2)
    car.position = Vector3(LANE_X[lane], 0.0, -142.0)
    car.visible = true
    car.process_mode = Node.PROCESS_MODE_INHERIT
    car.reset_physics_interpolation()
    traffic.append({"node": car, "lane": lane})


func _make_coin() -> Node3D:
    # Shared mesh/material resources keep the gold coin's detail while avoiding
    # dozens of duplicate mesh/material allocations during a race.
    if coin_rim_mesh == null:
        coin_rim_mesh = CylinderMesh.new()
        coin_rim_mesh.top_radius = 0.61
        coin_rim_mesh.bottom_radius = 0.61
        coin_rim_mesh.height = 0.16
        coin_rim_mesh.radial_segments = 16
        coin_face_mesh = CylinderMesh.new()
        coin_face_mesh.top_radius = 0.47
        coin_face_mesh.bottom_radius = 0.47
        coin_face_mesh.height = 0.175
        coin_face_mesh.radial_segments = 16
        coin_glint_mesh = SphereMesh.new()
        coin_glint_mesh.radial_segments = 12
        coin_glint_mesh.rings = 8
        coin_rim_material = _mat(Color(0.76, 0.37, 0.035), 0.22, 0.82)
        coin_face_material = _mat(Color(1.0, 0.77, 0.11), 0.2, 0.72, Color(0.40, 0.20, 0.01))
        coin_glint_material = _mat(Color(1.0, 0.93, 0.41), 0.25, 0.55)

    var coin := Node3D.new()
    coin.name = "GoldCoin"
    var rim := MeshInstance3D.new()
    rim.mesh = coin_rim_mesh
    rim.rotation.x = PI * 0.5
    rim.material_override = coin_rim_material
    coin.add_child(rim)
    var face := MeshInstance3D.new()
    face.mesh = coin_face_mesh
    face.rotation.x = PI * 0.5
    face.material_override = coin_face_material
    coin.add_child(face)
    var glint := MeshInstance3D.new()
    glint.mesh = coin_glint_mesh
    glint.scale = Vector3(0.13, 0.13, 0.13)
    glint.position = Vector3(0.0, 0.0, -0.10)
    glint.material_override = coin_glint_material
    coin.add_child(glint)
    return coin


func _spawn_coin_train() -> void:
    var available_coins: Array[Node3D] = []
    for pooled_coin in coin_pool:
        if is_instance_valid(pooled_coin) and not pooled_coin.visible:
            available_coins.append(pooled_coin)
            if available_coins.size() >= 4:
                break
    if available_coins.size() < 4:
        return

    var lane: int = rng.randi_range(0, 2)
    for i in range(4):
        var coin: Node3D = available_coins[i]
        coin.position = Vector3(LANE_X[lane], 1.35, -121.0 - float(i) * 3.1)
        coin.rotation = Vector3.ZERO
        coin.visible = true
        coin.process_mode = Node.PROCESS_MODE_INHERIT
        coin.reset_physics_interpolation()
        coin_rows.append({"node": coin, "lane": lane, "bob_phase": rng.randf_range(0.0, TAU)})

func _update_traffic(delta: float) -> void:
    for i in range(traffic.size() - 1, -1, -1):
        var item: Dictionary = traffic[i]
        var car: Node3D = item["node"]
        car.position.z += world_speed * delta
        if car.position.z > 26.0:
            car.visible = false
            car.process_mode = Node.PROCESS_MODE_DISABLED
            car.position = Vector3(0.0, -1000.0, 0.0)
            car.reset_physics_interpolation()
            traffic.remove_at(i)
            continue
        var lateral_distance: float = absf(car.position.x - player_car.position.x)
        var longitudinal_distance: float = absf(car.position.z - player_car.position.z)
        # Check actual car positions rather than the target lane. The player may
        # still be visibly moving between lanes when a traffic car arrives.
        if lateral_distance <= 1.82 and longitudinal_distance <= 3.20:
            _crash()
            return

func _update_coins(delta: float) -> void:
    for i in range(coin_rows.size() - 1, -1, -1):
        var item: Dictionary = coin_rows[i]
        var coin: Node3D = item["node"]
        coin.position.z += world_speed * delta
        coin.rotation.y += delta * 2.8
        coin.position.y = 1.35 + sin(distance_run * 0.12 + float(item["bob_phase"])) * 0.10
        if coin.position.z > 2.1:
            coin.visible = false
            coin.process_mode = Node.PROCESS_MODE_DISABLED
            coin.position = Vector3(0.0, -1000.0, 0.0)
            coin.reset_physics_interpolation()
            coin_rows.remove_at(i)
            continue
        if absf(coin.position.x - player_car.position.x) <= 1.45 and absf(coin.position.z - player_car.position.z) < 1.7:
            coins_collected += 1
            coin.visible = false
            coin.process_mode = Node.PROCESS_MODE_DISABLED
            coin.position = Vector3(0.0, -1000.0, 0.0)
            coin.reset_physics_interpolation()
            coin_rows.remove_at(i)
            camera_bump = minf(0.12, camera_bump + 0.035)

func _crash() -> void:
    game_over = true
    camera_bump = 0.35
    player_car.rotation.z = 0.24
    over_score_label.text = "SCORE  %06d    •    COINS  %02d" % [score, coins_collected]
    game_over_panel.visible = true
    steering_hint.text = "CRASH!  Tap restart to race again."


func _restart() -> void:
    for item in traffic:
        var car := item["node"] as Node3D
        if is_instance_valid(car):
            car.visible = false
            car.process_mode = Node.PROCESS_MODE_DISABLED
            car.position = Vector3(0.0, -1000.0, 0.0)
            car.reset_physics_interpolation()
    for item in coin_rows:
        var coin := item["node"] as Node3D
        if is_instance_valid(coin):
            coin.visible = false
            coin.process_mode = Node.PROCESS_MODE_DISABLED
            coin.position = Vector3(0.0, -1000.0, 0.0)
            coin.reset_physics_interpolation()
    traffic.clear()
    coin_rows.clear()
    player_lane = 1
    player_car.position = Vector3(LANE_X[player_lane], 0.0, 3.0)
    player_car.rotation = Vector3.ZERO
    player_car.reset_physics_interpolation()
    world_speed = 24.0
    distance_run = 0.0
    score = 0
    coins_collected = 0
    traffic_timer = 1.0
    coin_timer = 2.7
    hud_refresh_timer = 0.0
    game_over = false
    camera_bump = 0.0
    if is_instance_valid(camera):
        camera.position = Vector3(0.0, 6.2, 13.0)
        camera.look_at(Vector3(0.0, 0.65, -25.0), Vector3.UP)
        camera.reset_physics_interpolation()
    game_over_panel.visible = false
    steering_hint.text = "TAP LEFT / RIGHT TO CHANGE LANES"
    _update_hud()

func _make_ui() -> void:
    var layer := CanvasLayer.new()
    layer.layer = 5
    add_child(layer)

    var ui := Control.new()
    ui_root = ui
    ui.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    ui.mouse_filter = Control.MOUSE_FILTER_PASS
    layer.add_child(ui)

    # DisplayServer reports the usable rectangle around Android cutouts/system insets.
    # Ratios keep the offsets correct across phones with different resolutions.
    var safe_area := DisplayServer.get_display_safe_area()
    var screen_size := DisplayServer.screen_get_size()
    var safe_left: float = 0.0
    var safe_top: float = 0.0
    var safe_right: float = 0.0
    var safe_bottom: float = 0.0
    if screen_size.x > 0 and screen_size.y > 0:
        safe_left = clampf(float(safe_area.position.x) / float(screen_size.x), 0.0, 0.10)
        safe_top = clampf(float(safe_area.position.y) / float(screen_size.y), 0.0, 0.10)
        safe_right = clampf(float(screen_size.x - safe_area.position.x - safe_area.size.x) / float(screen_size.x), 0.0, 0.10)
        safe_bottom = clampf(float(screen_size.y - safe_area.position.y - safe_area.size.y) / float(screen_size.y), 0.0, 0.10)

    var top_panel := PanelContainer.new()
    top_panel.anchor_left = 0.025 + safe_left
    top_panel.anchor_right = 0.975 - safe_right
    top_panel.anchor_top = 0.018 + safe_top
    top_panel.anchor_bottom = 0.115 + safe_top
    var top_style := StyleBoxFlat.new()
    top_style.bg_color = Color(0.035, 0.11, 0.20, 0.76)
    top_style.corner_radius_top_left = 24
    top_style.corner_radius_top_right = 24
    top_style.corner_radius_bottom_left = 24
    top_style.corner_radius_bottom_right = 24
    top_style.content_margin_left = 18.0
    top_style.content_margin_right = 18.0
    top_style.content_margin_top = 5.0
    top_style.content_margin_bottom = 5.0
    top_panel.add_theme_stylebox_override("panel", top_style)
    ui.add_child(top_panel)

    var top_row := HBoxContainer.new()
    top_row.add_theme_constant_override("separation", 10)
    top_panel.add_child(top_row)

    score_label = Label.new()
    score_label.text = "SCORE  000000"
    score_label.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    score_label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    score_label.add_theme_font_size_override("font_size", 24)
    score_label.add_theme_color_override("font_color", Color(1.0, 1.0, 1.0))
    score_label.add_theme_color_override("font_shadow_color", Color(0.0, 0.0, 0.0, 0.65))
    score_label.add_theme_constant_override("shadow_offset_x", 2)
    score_label.add_theme_constant_override("shadow_offset_y", 2)
    top_row.add_child(score_label)

    coins_label = Label.new()
    coins_label.text = "COINS  00"
    coins_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
    coins_label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    coins_label.add_theme_font_size_override("font_size", 23)
    coins_label.add_theme_color_override("font_color", Color(1.0, 0.83, 0.20))
    top_row.add_child(coins_label)

    var title := Label.new()
    title.text = "T U R B O   T R A I L S   3D"
    title.anchor_left = 0.10 + safe_left
    title.anchor_right = 0.90 - safe_right
    title.anchor_top = 0.125 + safe_top
    title.anchor_bottom = 0.17 + safe_top
    title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    title.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    title.add_theme_font_size_override("font_size", 21)
    title.add_theme_color_override("font_color", Color(1.0, 1.0, 1.0, 0.92))
    title.add_theme_color_override("font_shadow_color", Color(0.02, 0.12, 0.15, 0.9))
    title.add_theme_constant_override("shadow_offset_x", 2)
    title.add_theme_constant_override("shadow_offset_y", 2)
    ui.add_child(title)

    var asset_credit := Label.new()
    asset_credit.text = "CC0 CARS + TREES  •  POLY HAVEN PBR + SKY"
    asset_credit.anchor_left = 0.10 + safe_left
    asset_credit.anchor_right = 0.90 - safe_right
    asset_credit.anchor_top = 0.17 + safe_top
    asset_credit.anchor_bottom = 0.195 + safe_top
    asset_credit.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    asset_credit.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    asset_credit.add_theme_font_size_override("font_size", 11)
    asset_credit.add_theme_color_override("font_color", Color(0.10, 0.24, 0.32, 0.94))
    ui.add_child(asset_credit)

    steering_hint = Label.new()
    steering_hint.text = "TAP LEFT / RIGHT TO CHANGE LANES"
    steering_hint.anchor_left = 0.10 + safe_left
    steering_hint.anchor_right = 0.90 - safe_right
    # Keep the steering guide below the title; never paint text over the player car.
    steering_hint.anchor_top = 0.205 + safe_top
    steering_hint.anchor_bottom = 0.242 + safe_top
    steering_hint.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    steering_hint.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    steering_hint.add_theme_font_size_override("font_size", 15)
    steering_hint.add_theme_color_override("font_color", Color(1.0, 1.0, 1.0, 0.94))
    steering_hint.add_theme_color_override("font_shadow_color", Color(0.0, 0.0, 0.0, 0.9))
    steering_hint.add_theme_constant_override("shadow_offset_x", 2)
    steering_hint.add_theme_constant_override("shadow_offset_y", 2)
    ui.add_child(steering_hint)

    var left_button := _make_control_button("◀", Color(0.03, 0.64, 0.88, 0.83))
    left_button.anchor_left = 0.045 + safe_left
    left_button.anchor_right = 0.425 - safe_right
    left_button.anchor_top = 0.90 - safe_bottom
    left_button.anchor_bottom = 0.99 - safe_bottom
    left_button.pressed.connect(_steer_left)
    ui.add_child(left_button)

    var right_button := _make_control_button("▶", Color(0.03, 0.64, 0.88, 0.83))
    right_button.anchor_left = 0.575 + safe_left
    right_button.anchor_right = 0.955 - safe_right
    right_button.anchor_top = 0.90 - safe_bottom
    right_button.anchor_bottom = 0.99 - safe_bottom
    right_button.pressed.connect(_steer_right)
    ui.add_child(right_button)

    game_over_panel = PanelContainer.new()
    game_over_panel.anchor_left = 0.09 + safe_left
    game_over_panel.anchor_right = 0.91 - safe_right
    game_over_panel.anchor_top = 0.34
    game_over_panel.anchor_bottom = 0.68
    var over_style := StyleBoxFlat.new()
    over_style.bg_color = Color(0.055, 0.14, 0.28, 0.96)
    over_style.border_color = Color(0.17, 0.88, 0.91, 1.0)
    over_style.set_border_width_all(3)
    over_style.corner_radius_top_left = 30
    over_style.corner_radius_top_right = 30
    over_style.corner_radius_bottom_left = 30
    over_style.corner_radius_bottom_right = 30
    over_style.content_margin_left = 20.0
    over_style.content_margin_right = 20.0
    over_style.content_margin_top = 18.0
    over_style.content_margin_bottom = 18.0
    game_over_panel.add_theme_stylebox_override("panel", over_style)
    game_over_panel.visible = false
    ui.add_child(game_over_panel)

    var over_column := VBoxContainer.new()
    over_column.add_theme_constant_override("separation", 17)
    over_column.alignment = BoxContainer.ALIGNMENT_CENTER
    game_over_panel.add_child(over_column)

    var over_title := Label.new()
    over_title.text = "WIPEOUT!"
    over_title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    over_title.add_theme_font_size_override("font_size", 43)
    over_title.add_theme_color_override("font_color", Color(1.0, 0.85, 0.27))
    over_column.add_child(over_title)

    over_score_label = Label.new()
    over_score_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    over_score_label.add_theme_font_size_override("font_size", 20)
    over_score_label.add_theme_color_override("font_color", Color.WHITE)
    over_column.add_child(over_score_label)

    var restart_button := _make_control_button("RACE AGAIN  ↻", Color(0.09, 0.78, 0.68, 1.0))
    restart_button.custom_minimum_size = Vector2(0, 72)
    restart_button.add_theme_font_size_override("font_size", 24)
    restart_button.pressed.connect(_restart)
    over_column.add_child(restart_button)


func _make_control_button(text_value: String, base_color: Color) -> Button:
    var button := Button.new()
    button.text = text_value
    button.action_mode = BaseButton.ACTION_MODE_BUTTON_PRESS
    button.focus_mode = Control.FOCUS_NONE
    button.add_theme_font_size_override("font_size", 44)
    button.add_theme_color_override("font_color", Color.WHITE)
    button.add_theme_color_override("font_hover_color", Color.WHITE)
    button.add_theme_color_override("font_pressed_color", Color.WHITE)
    var normal := StyleBoxFlat.new()
    normal.bg_color = base_color
    normal.border_color = base_color.lightened(0.35)
    normal.set_border_width_all(2)
    normal.corner_radius_top_left = 26
    normal.corner_radius_top_right = 26
    normal.corner_radius_bottom_left = 26
    normal.corner_radius_bottom_right = 26
    normal.shadow_color = Color(0.0, 0.0, 0.0, 0.20)
    normal.shadow_size = 7
    var hover := normal.duplicate() as StyleBoxFlat
    hover.bg_color = base_color.lightened(0.12)
    var pressed := normal.duplicate() as StyleBoxFlat
    pressed.bg_color = base_color.darkened(0.14)
    button.add_theme_stylebox_override("normal", normal)
    button.add_theme_stylebox_override("hover", hover)
    button.add_theme_stylebox_override("pressed", pressed)
    button.add_theme_stylebox_override("focus", normal)
    return button


func _unhandled_input(event: InputEvent) -> void:
    # Keyboard controls make the game testable on desktop as well as touch devices.
    if game_over or not (event is InputEventKey):
        return
    var key_event := event as InputEventKey
    if not key_event.pressed or key_event.echo:
        return
    if key_event.keycode == KEY_LEFT or key_event.keycode == KEY_A:
        _steer_left()
    elif key_event.keycode == KEY_RIGHT or key_event.keycode == KEY_D:
        _steer_right()


func _steer_left() -> void:
    if not game_over:
        player_lane = maxi(0, player_lane - 1)


func _steer_right() -> void:
    if not game_over:
        player_lane = mini(2, player_lane + 1)


func _update_hud() -> void:
    if is_instance_valid(score_label):
        var new_score_text: String = "SCORE  %06d" % score
        if score_label.text != new_score_text:
            score_label.text = new_score_text
    if is_instance_valid(coins_label):
        var new_coins_text: String = "COINS  %02d" % coins_collected
        if coins_label.text != new_coins_text:
            coins_label.text = new_coins_text


func _get_grass_material() -> StandardMaterial3D:
    if grass_material_cache != null:
        return grass_material_cache
    # Natural leafy-ground albedo, a matte finish, and no fine normal map prevent
    # distracting high-frequency sparkle as the camera travels down the road.
    var grass := _mat(Color(0.70, 0.88, 0.54), 0.99)
    var grass_diffuse := _asset("res://assets/textures/leafy_grass_diffuse.jpg") as Texture2D
    var grass_roughness := _asset("res://assets/textures/leafy_grass_roughness.jpg") as Texture2D
    if grass_diffuse != null:
        grass.albedo_texture = grass_diffuse
    if grass_roughness != null:
        grass.roughness_texture = grass_roughness
    grass.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS_ANISOTROPIC
    # Repeat the 1K PBR tile across the large terrain plane instead of stretching it.
    grass.uv1_scale = Vector3(28.0, 1.0, 66.0)
    grass_material_cache = grass
    return grass_material_cache


func _get_road_material() -> StandardMaterial3D:
    if road_material_cache != null:
        return road_material_cache
    var road := _mat(Color(0.72, 0.74, 0.78), 0.94)
    var road_diffuse := _asset("res://assets/textures/asphalt_track_diffuse.jpg") as Texture2D
    var road_normal := _asset("res://assets/textures/asphalt_track_normal.jpg") as Texture2D
    var road_roughness := _asset("res://assets/textures/asphalt_track_roughness.jpg") as Texture2D
    if road_diffuse != null:
        road.albedo_texture = road_diffuse
        road.uv1_scale = Vector3(1.0, 1.0, 7.0)
        road.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS_ANISOTROPIC
    if road_normal != null:
        road.normal_enabled = true
        road.normal_texture = road_normal
    if road_roughness != null:
        road.roughness_texture = road_roughness
    road_material_cache = road
    return road_material_cache


func _mat(color: Color, roughness: float = 0.72, metallic: float = 0.0, emission_color: Color = Color.BLACK) -> StandardMaterial3D:
    var key: String = "%s_%.2f_%.2f_%s" % [color.to_html(), roughness, metallic, emission_color.to_html()]
    if material_cache.has(key):
        return material_cache[key] as StandardMaterial3D

    var material := StandardMaterial3D.new()
    material.albedo_color = color
    material.roughness = roughness
    material.metallic = metallic
    if emission_color != Color.BLACK:
        material.emission_enabled = true
        material.emission = emission_color
        material.emission_energy_multiplier = 1.2
    material_cache[key] = material
    return material


func _get_box_mesh(size: Vector3) -> BoxMesh:
    var key: String = "%.3f_%.3f_%.3f" % [size.x, size.y, size.z]
    if box_mesh_cache.has(key):
        return box_mesh_cache[key] as BoxMesh
    var mesh := BoxMesh.new()
    mesh.size = size
    box_mesh_cache[key] = mesh
    return mesh


func _add_box(parent: Node3D, size: Vector3, position_value: Vector3, material_value: Material, casts_shadow: bool = true) -> MeshInstance3D:
    var instance := MeshInstance3D.new()
    instance.mesh = _get_box_mesh(size)
    instance.position = position_value
    instance.material_override = material_value
    if not casts_shadow:
        instance.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
    parent.add_child(instance)
    return instance


func _make_sphere(scale_value: Vector3, position_value: Vector3, color_value: Color, casts_shadow: bool = true) -> MeshInstance3D:
    var sphere_mesh := SphereMesh.new()
    sphere_mesh.radius = 1.0
    sphere_mesh.height = 2.0
    sphere_mesh.radial_segments = sphere_radial
    sphere_mesh.rings = sphere_rings
    var instance := MeshInstance3D.new()
    instance.mesh = sphere_mesh
    instance.scale = scale_value
    instance.position = position_value
    instance.material_override = _mat(color_value, 0.88)
    if not casts_shadow:
        instance.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
    return instance


func _add_sphere(parent: Node3D, scale_value: Vector3, position_value: Vector3, color_value: Color, roughness: float = 0.82, metallic: float = 0.0, casts_shadow: bool = false) -> MeshInstance3D:
    var instance := _make_sphere(scale_value, position_value, color_value, casts_shadow)
    instance.material_override = _mat(color_value, roughness, metallic)
    parent.add_child(instance)
    return instance
