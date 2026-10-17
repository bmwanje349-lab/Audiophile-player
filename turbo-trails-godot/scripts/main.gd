extends Node3D

# Turbo Trails 3D — Godot 4 project, built from real 3D meshes.
# Lightweight low-poly art, GL Compatibility renderer, no large third-party assets.

const SEGMENT_LENGTH: float = 36.0
const SEGMENT_COUNT: int = 14
const ROAD_HALF_WIDTH: float = 6.0
const LANE_X: Array[float] = [-3.8, 0.0, 3.8]
const CAR_PAINTS: Array[Color] = [
    Color(1.0, 0.22, 0.30), Color(1.0, 0.55, 0.12),
    Color(0.57, 0.28, 1.0), Color(0.12, 0.76, 1.0),
    Color(1.0, 0.30, 0.68), Color(0.34, 0.86, 0.37)
]

var rng := RandomNumberGenerator.new()
var material_cache: Dictionary = {}
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


func _ready() -> void:
    rng.randomize()
    _make_environment()
    _make_ground_and_hills()
    _make_road()
    _make_roadside_scenery()
    player_car = _make_car(Color(0.03, 0.86, 0.78), true)
    player_car.position = Vector3(LANE_X[player_lane], 0.0, 3.0)
    add_child(player_car)
    _make_ui()
    _update_hud()


func _process(delta: float) -> void:
    if game_over:
        camera_bump = move_toward(camera_bump, 0.0, delta * 0.25)
        return

    distance_run += world_speed * delta
    score = int(distance_run * 2.0) + coins_collected * 100
    world_speed = minf(36.0, 24.0 + distance_run * 0.012)

    for segment in road_segments:
        segment.position.z += world_speed * delta
        if segment.position.z > SEGMENT_LENGTH * 0.72:
            segment.position.z -= SEGMENT_LENGTH * SEGMENT_COUNT

    for prop in scenery:
        prop.position.z += world_speed * delta * 0.94
        if prop.position.z > 28.0:
            var side: float = -1.0 if prop.position.x < 0.0 else 1.0
            prop.position.z = -160.0 - rng.randf_range(0.0, 45.0)
            prop.position.x = side * rng.randf_range(8.0, 14.0)

    player_car.position.x = move_toward(
        player_car.position.x, LANE_X[player_lane], delta * 14.0
    )
    var steer_offset: float = LANE_X[player_lane] - player_car.position.x
    player_car.rotation.z = clampf(-steer_offset * 0.045, -0.10, 0.10)
    camera.position.x = lerpf(camera.position.x, player_car.position.x * 0.20, delta * 2.0)
    camera.position.y = 7.0 + sin(distance_run * 0.035) * 0.055 + camera_bump
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
    _update_hud()


func _make_environment() -> void:
    var env := Environment.new()
    env.background_mode = Environment.BG_SKY
    env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
    env.ambient_light_energy = 0.85
    env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
    env.tonemap_exposure = 1.12
    env.fog_enabled = true
    env.fog_light_color = Color(0.64, 0.86, 0.98)
    env.fog_density = 0.0015

    var sky := Sky.new()
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
    sun.light_energy = 1.25
    sun.shadow_enabled = true
    sun.directional_shadow_max_distance = 85.0
    add_child(sun)

    camera = Camera3D.new()
    camera.name = "FollowCamera"
    camera.position = Vector3(0.0, 7.0, 12.0)
    camera.fov = 62.0
    camera.current = true
    add_child(camera)
    camera.look_at(Vector3(0.0, 0.9, -10.0), Vector3.UP)


func _make_ground_and_hills() -> void:
    var ground_mesh := PlaneMesh.new()
    ground_mesh.size = Vector2(260.0, 620.0)
    var ground := MeshInstance3D.new()
    ground.name = "EndlessGreenGround"
    ground.mesh = ground_mesh
    ground.position = Vector3(0.0, -0.34, -120.0)
    ground.material_override = _mat(Color(0.16, 0.66, 0.30), 0.95)
    add_child(ground)

    var hill_colors: Array[Color] = [
        Color(0.17, 0.68, 0.36), Color(0.29, 0.78, 0.40),
        Color(0.12, 0.59, 0.37), Color(0.43, 0.80, 0.39)
    ]
    for i in range(18):
        var side: float = -1.0 if i % 2 == 0 else 1.0
        var row: int = int(i / 2)
        var hill := _make_sphere(
            Vector3(rng.randf_range(8.0, 15.0), rng.randf_range(4.0, 8.0), rng.randf_range(13.0, 23.0)),
            Vector3(side * rng.randf_range(15.0, 43.0), rng.randf_range(2.7, 5.0), -70.0 - row * 12.0),
            hill_colors[i % hill_colors.size()]
        )
        hill.name = "RollingHill"
        add_child(hill)

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


func _make_road() -> void:
    for i in range(SEGMENT_COUNT):
        var segment := Node3D.new()
        segment.name = "RoadSegment_%02d" % i
        segment.position.z = 18.0 - float(i) * SEGMENT_LENGTH
        _add_box(segment, Vector3(12.0, 0.22, SEGMENT_LENGTH), Vector3(0.0, -0.15, 0.0), _mat(Color(0.20, 0.23, 0.30), 0.92))
        _add_box(segment, Vector3(0.24, 0.035, SEGMENT_LENGTH), Vector3(-5.86, -0.025, 0.0), _mat(Color(1.0, 0.82, 0.35), 0.7))
        _add_box(segment, Vector3(0.24, 0.035, SEGMENT_LENGTH), Vector3(5.86, -0.025, 0.0), _mat(Color(1.0, 0.82, 0.35), 0.7))

        for dash_index in range(6):
            var dash_z: float = -SEGMENT_LENGTH * 0.42 + float(dash_index) * 6.0
            for divider_x in [-1.9, 1.9]:
                _add_box(
                    segment, Vector3(0.105, 0.035, 2.8),
                    Vector3(divider_x, -0.025, dash_z),
                    _mat(Color(0.96, 0.97, 0.89), 0.75)
                )

        for curb_index in range(6):
            var curb_z: float = -SEGMENT_LENGTH * 0.42 + float(curb_index) * 6.0
            var curb_color: Color = Color(1.0, 0.36, 0.31) if curb_index % 2 == 0 else Color(1.0, 0.95, 0.81)
            _add_box(segment, Vector3(0.28, 0.13, 2.7), Vector3(-6.08, -0.02, curb_z), _mat(curb_color, 0.7))
            _add_box(segment, Vector3(0.28, 0.13, 2.7), Vector3(6.08, -0.02, curb_z), _mat(curb_color, 0.7))

        add_child(segment)
        road_segments.append(segment)


func _make_roadside_scenery() -> void:
    for i in range(34):
        var side: float = -1.0 if i % 2 == 0 else 1.0
        var prop := _make_tree(
            Vector3(
                side * rng.randf_range(8.0, 14.0),
                0.0,
                -float(i) * 8.8 - rng.randf_range(0.0, 12.0)
            ),
            i
        )
        add_child(prop)
        scenery.append(prop)


func _make_tree(world_position: Vector3, style_index: int) -> Node3D:
    var tree := Node3D.new()
    tree.name = "ColourfulRoadsideTree"
    tree.position = world_position

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
    var car := Node3D.new()
    car.name = "PlayerSportsCar" if is_player else "TrafficCar"
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
    var lane: int = rng.randi_range(0, 2)
    var car := _make_car(CAR_PAINTS[rng.randi_range(0, CAR_PAINTS.size() - 1)], false)
    car.position = Vector3(LANE_X[lane], 0.0, -142.0)
    add_child(car)
    traffic.append({"node": car, "lane": lane})


func _spawn_coin_train() -> void:
    var lane: int = rng.randi_range(0, 2)
    for i in range(4):
        var coin := Node3D.new()
        coin.name = "GoldCoin"
        coin.position = Vector3(LANE_X[lane], 1.35, -121.0 - float(i) * 3.1)
        var rim_mesh := CylinderMesh.new()
        rim_mesh.top_radius = 0.61
        rim_mesh.bottom_radius = 0.61
        rim_mesh.height = 0.16
        rim_mesh.radial_segments = 16
        var rim := MeshInstance3D.new()
        rim.mesh = rim_mesh
        rim.rotation.x = PI * 0.5
        rim.material_override = _mat(Color(0.76, 0.37, 0.035), 0.22, 0.82)
        coin.add_child(rim)
        var face_mesh := CylinderMesh.new()
        face_mesh.top_radius = 0.47
        face_mesh.bottom_radius = 0.47
        face_mesh.height = 0.175
        face_mesh.radial_segments = 16
        var face := MeshInstance3D.new()
        face.mesh = face_mesh
        face.rotation.x = PI * 0.5
        face.material_override = _mat(Color(1.0, 0.77, 0.11), 0.2, 0.72, Color(0.40, 0.20, 0.01))
        coin.add_child(face)
        _add_sphere(coin, Vector3(0.13, 0.13, 0.13), Vector3(0.0, 0.0, -0.10), Color(1.0, 0.93, 0.41), 0.25, 0.55)
        add_child(coin)
        coin_rows.append({"node": coin, "lane": lane})


func _update_traffic(delta: float) -> void:
    for i in range(traffic.size() - 1, -1, -1):
        var item: Dictionary = traffic[i]
        var car: Node3D = item["node"]
        car.position.z += world_speed * delta
        if car.position.z > 26.0:
            car.queue_free()
            traffic.remove_at(i)
            continue
        if int(item["lane"]) == player_lane and car.position.z > -1.2 and car.position.z < 2.1:
            _crash()
            return


func _update_coins(delta: float) -> void:
    for i in range(coin_rows.size() - 1, -1, -1):
        var item: Dictionary = coin_rows[i]
        var coin: Node3D = item["node"]
        coin.position.z += world_speed * delta
        coin.rotation.y += delta * 2.8
        coin.position.y = 1.35 + sin(distance_run * 0.12 + float(i)) * 0.10
        if coin.position.z > 2.1:
            coin.queue_free()
            coin_rows.remove_at(i)
            continue
        if int(item["lane"]) == player_lane and absf(coin.position.z) < 1.7:
            coins_collected += 1
            coin.queue_free()
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
        (item["node"] as Node3D).queue_free()
    for item in coin_rows:
        (item["node"] as Node3D).queue_free()
    traffic.clear()
    coin_rows.clear()
    player_lane = 1
    player_car.position = Vector3(LANE_X[player_lane], 0.0, 3.0)
    player_car.rotation = Vector3.ZERO
    world_speed = 24.0
    distance_run = 0.0
    score = 0
    coins_collected = 0
    traffic_timer = 1.0
    coin_timer = 2.7
    game_over = false
    camera_bump = 0.0
    game_over_panel.visible = false
    steering_hint.text = "TAP LEFT / RIGHT TO CHANGE LANES"
    _update_hud()


func _make_ui() -> void:
    var layer := CanvasLayer.new()
    layer.layer = 5
    add_child(layer)

    var ui := Control.new()
    ui.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    ui.mouse_filter = Control.MOUSE_FILTER_PASS
    layer.add_child(ui)

    var top_panel := PanelContainer.new()
    top_panel.anchor_left = 0.025
    top_panel.anchor_right = 0.975
    top_panel.anchor_top = 0.018
    top_panel.anchor_bottom = 0.115
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
    title.anchor_left = 0.10
    title.anchor_right = 0.90
    title.anchor_top = 0.125
    title.anchor_bottom = 0.17
    title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    title.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    title.add_theme_font_size_override("font_size", 21)
    title.add_theme_color_override("font_color", Color(1.0, 1.0, 1.0, 0.92))
    title.add_theme_color_override("font_shadow_color", Color(0.02, 0.12, 0.15, 0.9))
    title.add_theme_constant_override("shadow_offset_x", 2)
    title.add_theme_constant_override("shadow_offset_y", 2)
    ui.add_child(title)

    steering_hint = Label.new()
    steering_hint.text = "TAP LEFT / RIGHT TO CHANGE LANES"
    steering_hint.anchor_left = 0.10
    steering_hint.anchor_right = 0.90
    steering_hint.anchor_top = 0.72
    steering_hint.anchor_bottom = 0.78
    steering_hint.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    steering_hint.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    steering_hint.add_theme_font_size_override("font_size", 19)
    steering_hint.add_theme_color_override("font_color", Color(1.0, 1.0, 1.0, 0.94))
    steering_hint.add_theme_color_override("font_shadow_color", Color(0.0, 0.0, 0.0, 0.9))
    steering_hint.add_theme_constant_override("shadow_offset_x", 2)
    steering_hint.add_theme_constant_override("shadow_offset_y", 2)
    ui.add_child(steering_hint)

    var left_button := _make_control_button("◀", Color(0.03, 0.64, 0.88, 0.83))
    left_button.anchor_left = 0.045
    left_button.anchor_right = 0.425
    left_button.anchor_top = 0.81
    left_button.anchor_bottom = 0.955
    left_button.pressed.connect(_steer_left)
    ui.add_child(left_button)

    var right_button := _make_control_button("▶", Color(0.03, 0.64, 0.88, 0.83))
    right_button.anchor_left = 0.575
    right_button.anchor_right = 0.955
    right_button.anchor_top = 0.81
    right_button.anchor_bottom = 0.955
    right_button.pressed.connect(_steer_right)
    ui.add_child(right_button)

    game_over_panel = PanelContainer.new()
    game_over_panel.anchor_left = 0.09
    game_over_panel.anchor_right = 0.91
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


func _steer_left() -> void:
    if not game_over:
        player_lane = maxi(0, player_lane - 1)


func _steer_right() -> void:
    if not game_over:
        player_lane = mini(2, player_lane + 1)


func _update_hud() -> void:
    if is_instance_valid(score_label):
        score_label.text = "SCORE  %06d" % score
    if is_instance_valid(coins_label):
        coins_label.text = "COINS  %02d" % coins_collected


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


func _add_box(parent: Node3D, size: Vector3, position_value: Vector3, material_value: Material) -> MeshInstance3D:
    var mesh := BoxMesh.new()
    mesh.size = size
    var instance := MeshInstance3D.new()
    instance.mesh = mesh
    instance.position = position_value
    instance.material_override = material_value
    parent.add_child(instance)
    return instance


func _make_sphere(scale_value: Vector3, position_value: Vector3, color_value: Color) -> MeshInstance3D:
    var sphere_mesh := SphereMesh.new()
    sphere_mesh.radius = 1.0
    sphere_mesh.height = 2.0
    sphere_mesh.radial_segments = 12
    sphere_mesh.rings = 8
    var instance := MeshInstance3D.new()
    instance.mesh = sphere_mesh
    instance.scale = scale_value
    instance.position = position_value
    instance.material_override = _mat(color_value, 0.88)
    return instance


func _add_sphere(parent: Node3D, scale_value: Vector3, position_value: Vector3, color_value: Color, roughness: float = 0.82, metallic: float = 0.0) -> MeshInstance3D:
    var instance := _make_sphere(scale_value, position_value, color_value)
    instance.material_override = _mat(color_value, roughness, metallic)
    parent.add_child(instance)
    return instance
