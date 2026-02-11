import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const ScreenShotApp());
}

class ScreenShotApp extends StatelessWidget {
  const ScreenShotApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Screen Shot',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  static const MethodChannel _channel = MethodChannel('screen_shot/overlay');
  static const Map<String, int> _ballColorOptions = <String, int>{
    'Blue': 0xFF2F6FFF,
    'Green': 0xFF20B26B,
    'Orange': 0xFFF59E0B,
    'Red': 0xFFEF4444,
    'Purple': 0xFF7C3AED,
    'Black': 0xFF111827,
  };

  String _status = 'Not started';
  bool _busy = false;
  bool _autoTried = false;
  bool _highPerformance = true;
  int _ballColor = 0xFF2F6FFF;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (_autoTried) return;
      _autoTried = true;
      await _applyHighPerformanceMode();
      await _applyBallColor();
      await _startOverlay(auto: true);
    });
  }

  Future<void> _applyHighPerformanceMode() async {
    if (!Platform.isAndroid) return;
    try {
      await _channel.invokeMethod('setHighPerformanceMode', <String, dynamic>{
        'enabled': _highPerformance,
      });
    } catch (_) {
      // ignore
    }
  }

  Future<void> _applyBallColor() async {
    if (!Platform.isAndroid) return;
    try {
      await _channel.invokeMethod('setBallColor', <String, dynamic>{
        'color': _ballColor,
      });
    } catch (_) {
      // ignore
    }
  }

  Future<void> _startOverlay({bool auto = false}) async {
    if (_busy) return;
    if (!Platform.isAndroid) {
      setState(() {
        _status = 'Android only';
      });
      return;
    }
    setState(() {
      _busy = true;
      _status = auto ? 'Starting...' : 'Requesting permissions...';
    });
    try {
      final overlayOk =
          await _channel.invokeMethod<bool>('requestOverlayPermission') ?? false;
      if (!overlayOk) {
        setState(() {
          _status = 'Please grant overlay permission';
          _busy = false;
        });
        return;
      }
      final projectionOk =
          await _channel.invokeMethod<bool>('requestMediaProjection') ?? false;
      if (!projectionOk) {
        setState(() {
          _status = 'Please grant screen capture permission';
          _busy = false;
        });
        return;
      }
      await _applyHighPerformanceMode();
      await _applyBallColor();
      await _channel.invokeMethod('startOverlayService');
      setState(() {
        _status = 'Overlay started';
        _busy = false;
      });
    } on PlatformException catch (e) {
      setState(() {
        _status = 'Start failed: ${e.message ?? e.code}';
        _busy = false;
      });
    }
  }

  Future<void> _stopOverlay() async {
    if (_busy) return;
    if (!Platform.isAndroid) {
      setState(() {
        _status = 'Android only';
      });
      return;
    }
    setState(() {
      _busy = true;
    });
    try {
      await _channel.invokeMethod('stopOverlayService');
      setState(() {
        _status = 'Stopped';
        _busy = false;
      });
    } on PlatformException catch (e) {
      setState(() {
        _status = 'Stop failed: ${e.message ?? e.code}';
        _busy = false;
      });
    }
  }

  Future<void> _toggleHighPerformance(bool enabled) async {
    setState(() {
      _highPerformance = enabled;
    });
    if (!Platform.isAndroid) return;
    try {
      await _channel.invokeMethod('setHighPerformanceMode', <String, dynamic>{
        'enabled': enabled,
      });
      setState(() {
        _status =
            enabled ? 'High performance mode enabled' : 'High performance mode disabled';
      });
    } on PlatformException catch (e) {
      setState(() {
        _status = 'Set failed: ${e.message ?? e.code}';
      });
    }
  }

  Future<void> _changeBallColor(int color) async {
    setState(() {
      _ballColor = color;
    });
    if (!Platform.isAndroid) return;
    try {
      await _channel.invokeMethod('setBallColor', <String, dynamic>{
        'color': color,
      });
      setState(() {
        _status = 'Floating ball color updated';
      });
    } on PlatformException catch (e) {
      setState(() {
        _status = 'Set failed: ${e.message ?? e.code}';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('LingShot')),
      body: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              '状态: $_status',
              style: Theme.of(context).textTheme.titleMedium,
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            SwitchListTile(
              value: _highPerformance,
              onChanged: _busy ? null : _toggleHighPerformance,
              title: const Text('高性能'),
              subtitle: Text(
                _highPerformance
                    ? '缩放仅预览矩形框，松手后渲染贴图'
                    : '缩放实时渲染贴图',
              ),
              contentPadding: EdgeInsets.zero,
            ),
            const SizedBox(height: 12),
            Text(
              '悬浮球颜色',
              style: Theme.of(context).textTheme.titleSmall,
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: _ballColorOptions.entries.map((entry) {
                final selected = _ballColor == entry.value;
                return ChoiceChip(
                  label: Text(entry.key),
                  selected: selected,
                  onSelected: _busy ? null : (_) => _changeBallColor(entry.value),
                  avatar: CircleAvatar(
                    radius: 8,
                    backgroundColor: Color(entry.value),
                  ),
                );
              }).toList(),
            ),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: _busy ? null : _startOverlay,
              child: const Text('启动悬浮球'),
            ),
            const SizedBox(height: 12),
            OutlinedButton(
              onPressed: _busy ? null : _stopOverlay,
              child: const Text('停止悬浮球'),
            ),
          ],
        ),
      ),
    );
  }
}
