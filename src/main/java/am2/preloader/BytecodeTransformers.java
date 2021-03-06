package am2.preloader;

import am2.LogHelper;
import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.Level;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Field;
import java.util.Iterator;

public class BytecodeTransformers implements IClassTransformer{

	@Override
	public byte[] transform(String name, String transformedName, byte[] bytes){
		if (transformedName.equals("am2.armor.ItemMageHood") && AM2PreloaderContainer.foundThaumcraft){
			LogHelper.info("Core: Altering definition of " + transformedName + " to be thaumcraft compatible.");
			ClassReader cr = new ClassReader(bytes);
			ClassNode cn = new ClassNode();
			cr.accept(cn, 0);

			cn.interfaces.add("thaumcraft/api/IGoggles");
			cn.interfaces.add("thaumcraft/api/nodes/IRevealer");

			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(cw);

			bytes = cw.toByteArray();
		}else if (transformedName.equals("net.minecraft.client.renderer.EntityRenderer")){
			LogHelper.info("Core: Altering definition of " + transformedName);
			bytes = alterEntityRenderer(bytes);
		}else if (transformedName.equals("net.minecraft.client.entity.EntityPlayerSP")){
			LogHelper.info("Core: Altering definition of " + transformedName);
			bytes = alterEntityPlayerSP(bytes);
		}else if (transformedName.equals("net.minecraft.entity.EntityPlayerMP")){
			LogHelper.info("Core: Altering definition of " + transformedName);
			bytes = alterEntityPlayerMP(bytes);
		}else if (transformedName.equals("net.minecraft.network.NetServerHandler")){
			LogHelper.info("Core: Altering definition of " + transformedName);
			bytes = alterNetServerHandler(bytes);
		}else if (transformedName.equals("net.minecraft.client.renderer.entity.RendererLivingEntity")){
			LogHelper.info("Core: Altering definition of " + transformedName);
			bytes = alterRendererLivingEntity(bytes);
		}else if (transformedName.equals("net.minecraft.world.World")){
			LogHelper.info("Core: Altering definition of " + transformedName);
			bytes = alterWorld(bytes);
		}

		return bytes;
	}

	private byte[] alterRendererLivingEntity(byte[] bytes){
		ClassReader cr = new ClassReader(bytes);
		ClassNode cn = new ClassNode();
		cr.accept(cn, 0);

		for (MethodNode mn : cn.methods){
			if (mn.name.equals("a") && mn.desc.equals("(Lsv;DDDFF)V")){ //doRenderLiving(EntityLivingBase, double, double, double, float, float)
				AbstractInsnNode target = null;
				LogHelper.debug("Core: Located target method " + mn.name + mn.desc);
				Iterator<AbstractInsnNode> instructions = mn.instructions.iterator();
				while (instructions.hasNext()){
					AbstractInsnNode node = instructions.next();
					if (node instanceof MethodInsnNode){
						MethodInsnNode method = (MethodInsnNode)node;
						if (method.name.equals("a") && method.desc.equals("(Lsv;DDD)V")){ //renderLivingAt(EntityLivingBase, double, double, double)
							target = node;
							break;
						}
					}
				}

				if (target != null){
					VarInsnNode aLoad = new VarInsnNode(Opcodes.ALOAD, 1);
					MethodInsnNode callout = new MethodInsnNode(Opcodes.INVOKESTATIC, "am2/utility/RenderUtilities", "setupShrinkRender", "(Lnet/minecraft/entity/EntityLivingBase;)V");

					mn.instructions.insert(target, aLoad);
					mn.instructions.insert(aLoad, callout);
					LogHelper.debug("Core: Success!  Inserted opcodes!");
					break;
				}
			}
		}

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);

		return cw.toByteArray();
	}

	private byte[] alterEntityRenderer(byte[] bytes){
		ClassReader cr = new ClassReader(bytes);
		ClassNode cn = new ClassNode();
		cr.accept(cn, 0);

		for (MethodNode mn : cn.methods){
			if (mn.name.equals("a") && mn.desc.equals("(FI)V")){ //setupCameraTransform
				AbstractInsnNode orientCameraNode = null;
				AbstractInsnNode gluPerspectiveNode = null;
				LogHelper.debug("Core: Located target method " + mn.name + mn.desc);
				Iterator<AbstractInsnNode> instructions = mn.instructions.iterator();
				while (instructions.hasNext()){
					AbstractInsnNode node = instructions.next();
					if (node instanceof MethodInsnNode){
						MethodInsnNode method = (MethodInsnNode)node;
						if (orientCameraNode == null && method.name.equals("g") && method.desc.equals("(F)V")){ //orient camera
							LogHelper.debug("Core: Located target method insn node: " + method.name + method.desc);
							orientCameraNode = node;
							continue;
						}else if (gluPerspectiveNode == null && method.name.equals("gluPerspective") && method.desc.equals("(FFFF)V")){
							LogHelper.debug("Core: Located target method insn node: " + method.name + method.desc);
							gluPerspectiveNode = node;
							continue;
						}
					}

					if (orientCameraNode != null && gluPerspectiveNode != null){
						//found all nodes we're looking for
						break;
					}
				}
				if (orientCameraNode != null){
					VarInsnNode floatset = new VarInsnNode(Opcodes.FLOAD, 1);
					MethodInsnNode callout = new MethodInsnNode(Opcodes.INVOKESTATIC, "am2/guis/AMGuiHelper", "shiftView", "(F)V");
					mn.instructions.insert(orientCameraNode, callout);
					mn.instructions.insert(orientCameraNode, floatset);
					LogHelper.debug("Core: Success!  Inserted callout function op (shift)!");
				}
				if (gluPerspectiveNode != null){
					VarInsnNode floatset = new VarInsnNode(Opcodes.FLOAD, 1);
					MethodInsnNode callout = new MethodInsnNode(Opcodes.INVOKESTATIC, "am2/guis/AMGuiHelper", "flipView", "(F)V");
					mn.instructions.insert(gluPerspectiveNode, callout);
					mn.instructions.insert(gluPerspectiveNode, floatset);
					LogHelper.debug("Core: Success!  Inserted callout function op (flip)!");
				}

			}else if (mn.name.equals("b") && mn.desc.equals("(F)V")){ //updateCameraAndRender
				AbstractInsnNode target = null;
				LogHelper.debug("Core: Located target method " + mn.name + mn.desc);
				Iterator<AbstractInsnNode> instructions = mn.instructions.iterator();
				AbstractInsnNode node = null;
				boolean mouseFound = false;
				while (instructions.hasNext()){
					node = instructions.next();
					//look for the line:
					//this.mc.mcProfiler.startSection("mouse");
					if (!mouseFound){
						if (node instanceof LdcInsnNode){
							if (((LdcInsnNode)node).cst.equals("mouse")){
								mouseFound = true;
							}
						}
					}else{
						if (node instanceof MethodInsnNode){
							MethodInsnNode method = (MethodInsnNode)node;
							if (method.name.equals("a") && method.desc.equals("(Ljava/lang/String;)V")){
								LogHelper.debug("Core: Located target method insn node: " + method.name + method.desc);
								target = node;
								break;
							}
						}
					}
				}

				if (target != null){
					int iRegister = AM2PreloaderContainer.foundOptifine ? 3 : 2;

					VarInsnNode aLoad = new VarInsnNode(Opcodes.ALOAD, 0);
					VarInsnNode fLoad = new VarInsnNode(Opcodes.FLOAD, 1);
					VarInsnNode iLoad = new VarInsnNode(Opcodes.ILOAD, iRegister);
					MethodInsnNode callout = new MethodInsnNode(Opcodes.INVOKESTATIC, "am2/guis/AMGuiHelper", "overrideMouseInput", "(Lnet/minecraft/client/renderer/EntityRenderer;FZ)Z");
					VarInsnNode iStore = new VarInsnNode(Opcodes.ISTORE, iRegister);

					mn.instructions.insert(target, iStore);
					mn.instructions.insert(target, callout);
					mn.instructions.insert(target, iLoad);
					mn.instructions.insert(target, fLoad);
					mn.instructions.insert(target, aLoad);
					LogHelper.debug("Core: Success!  Inserted opcodes!");
				}
			}
		}

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);

		return cw.toByteArray();
	}

	private byte[] alterEntityPlayerSP(byte[] bytes){
		ClassReader cr = new ClassReader(bytes);
		ClassNode cn = new ClassNode();
		cr.accept(cn, 0);

		for (MethodNode mn : cn.methods){
			if (mn.name.equals("e") && mn.desc.equals("()V")){ //onLivingUpdate
				AbstractInsnNode target = null;
				LogHelper.debug("Core: Located target method " + mn.name + mn.desc);
				Iterator<AbstractInsnNode> instructions = mn.instructions.iterator();
				//look for the line:
				//this.movementInput.updatePlayerMoveState();
				while (instructions.hasNext()){
					AbstractInsnNode node = instructions.next();
					if (node instanceof VarInsnNode && ((VarInsnNode)node).getOpcode() == Opcodes.ALOAD){ //this.
						node = instructions.next();
						if (node instanceof FieldInsnNode && ((FieldInsnNode)node).getOpcode() == Opcodes.GETFIELD){ //movementInput.
							node = instructions.next();
							if (node instanceof MethodInsnNode){
								MethodInsnNode method = (MethodInsnNode)node;
								if (method.name.equals("a") && method.desc.equals("()V")){ //updatePlayerMoveState
									LogHelper.debug("Core: Located target method insn node: " + method.name + method.desc);
									target = node;
									break;
								}
							}
						}
					}
				}

				if (target != null){
					MethodInsnNode callout = new MethodInsnNode(Opcodes.INVOKESTATIC, "am2/guis/AMGuiHelper", "overrideKeyboardInput", "()V");
					mn.instructions.insert(target, callout);
					LogHelper.debug("Core: Success!  Inserted operations!");
					break;
				}
			}
		}

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);

		return cw.toByteArray();
	}

	private byte[] alterWorld(byte[] bytes){
		ClassReader cr = new ClassReader(bytes);
		ClassNode cn = new ClassNode();
		cr.accept(cn, 0);

		for (MethodNode mn : cn.methods){
			if (mn.name.equals("a") && mn.desc.equals("(Lsa;Ljava/lang/String;FF)V")){ //playSoundAtEntity
				AbstractInsnNode target = null;
				LogHelper.debug("Core: Located target method " + mn.name + mn.desc);
				Iterator<AbstractInsnNode> instructions = mn.instructions.iterator();
				while (instructions.hasNext()){
					AbstractInsnNode node = instructions.next();
					if (node instanceof VarInsnNode &&
							((VarInsnNode)node).getOpcode() == Opcodes.ALOAD &&
							((VarInsnNode)node).var == 5){
						AbstractInsnNode potentialMatch = node;
						node = instructions.next();
						if (node instanceof FieldInsnNode &&
								((FieldInsnNode)node).getOpcode() == Opcodes.GETFIELD &&
								((FieldInsnNode)node).name.equals("name") &&
								((FieldInsnNode)node).desc.equals("Ljava/lang/String;") &&
								((FieldInsnNode)node).owner.equals("net/minecraftforge/event/entity/PlaySoundAtEntityEvent")){
							LogHelper.debug("Core: Located target method insn node: " + ((FieldInsnNode)node).name + ((FieldInsnNode)node).desc);
							target = potentialMatch;
							break;
						}
					}
				}

				if (target != null){
					VarInsnNode aload1 = new VarInsnNode(Opcodes.ALOAD, 1);
					VarInsnNode fload4 = new VarInsnNode(Opcodes.FLOAD, 4);
					MethodInsnNode callout = new MethodInsnNode(Opcodes.INVOKESTATIC, "am2/utility/EntityUtilities", "modifySoundPitch", "(Lsa;F)F");
					VarInsnNode fstore4 = new VarInsnNode(Opcodes.FSTORE, 4);
					mn.instructions.insertBefore(target, aload1);
					mn.instructions.insertBefore(target, fload4);
					mn.instructions.insertBefore(target, callout);
					mn.instructions.insertBefore(target, fstore4);
					LogHelper.debug("Core: Success!  Inserted operations!");
					break;
				}
			}
		}

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);

		return cw.toByteArray();
	}

	private byte[] alterEntityPlayerMP(byte[] bytes){
		ClassReader cr = new ClassReader(bytes);
		ClassNode cn = new ClassNode();
		cr.accept(cn, 0);

		for (MethodNode mn : cn.methods){
			LogHelper.debug("%s %s", mn.name, mn.desc);
			if (mn.name.equals("b_") && mn.desc.equals("(Luf;)V")){ //travelToDimension
				AbstractInsnNode target = null;
				LogHelper.debug("Core: Located target method " + mn.name + mn.desc);
				Iterator<AbstractInsnNode> instructions = mn.instructions.iterator();
				//look for the line:
				//this.worldObj.removeEntity(this)
				while (instructions.hasNext()){
					AbstractInsnNode node = instructions.next();
					if (node instanceof MethodInsnNode){
						MethodInsnNode method = (MethodInsnNode)node;
						if (method.name.equals("e") && method.desc.equals("(Lnn;)V")){ //removeEntity
							//instructions for the method go:
							//ALOAD 0
							//GETFIELD...
							//ALOAD 0
							//INVOKEVIRTUAL
							target = method.getPrevious().getPrevious().getPrevious(); //back up to the first ALOAD
							break;
						}
					}
				}

				if (target != null){
					//GETSTATIC am2/AMCore.proxy : Lam2/proxy/CommonProxy;
					FieldInsnNode proxyGet = new FieldInsnNode(Opcodes.GETSTATIC, "am2/AMCore", "proxy", "Lam2/proxy/CommonProxy");
					//GETFIELD am2/proxy/CommonProxy.playerTracker : Lam2/PlayerTracker;
					FieldInsnNode fieldGet = new FieldInsnNode(Opcodes.GETFIELD, "am2/proxy/CommonProxy", "playerTracker", "Lam2/PlayerTracker");
					//ALOAD 0
					VarInsnNode aLoad = new VarInsnNode(Opcodes.ALOAD, 0);
					//INVOKEVIRTUAL am2/PlayerTracker.onPlayerDeath (Lnet/minecraft/entity/player/EntityPlayer;)V
					MethodInsnNode callout = new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "am2/PlayerTracker", "onPlayerDeath", "(Luf;)V");

					mn.instructions.insertBefore(target, proxyGet);
					mn.instructions.insertBefore(target, fieldGet);
					mn.instructions.insertBefore(target, aLoad);
					mn.instructions.insertBefore(target, callout);

					LogHelper.debug("Core: Success!  Inserted opcodes!");
				}
			}
		}

		return bytes;
	}

	private byte[] alterNetServerHandler(byte[] bytes){
		ClassReader cr = new ClassReader(bytes);
		ClassNode cn = new ClassNode();
		cr.accept(cn, 0);

		for (MethodNode mn : cn.methods){
			if (mn.name.equals("a") && mn.desc.equals("(Leu;)V")){ //handleFlying
				AbstractInsnNode target = null;
				LogHelper.debug("Core: Located target method " + mn.name + mn.desc);
				Iterator<AbstractInsnNode> instructions = mn.instructions.iterator();

				//look for the line:
				//d4 = par1Packet10Flying.stance - par1Packet10Flying.yPosition;
				while (instructions.hasNext()){
					AbstractInsnNode node = instructions.next();
					if (node instanceof VarInsnNode && ((VarInsnNode)node).var == 1 && ((VarInsnNode)node).getOpcode() == Opcodes.ALOAD){ //ALOAD 1
						node = instructions.next();
						if (node instanceof FieldInsnNode && matchFieldNode((FieldInsnNode)node, Opcodes.GETFIELD, "eu", "d", "D")){ //GETFIELD net/minecraft/network/packet/Packet10Flying.stance
							node = instructions.next();
							if (node instanceof VarInsnNode && ((VarInsnNode)node).var == 1 && ((VarInsnNode)node).getOpcode() == Opcodes.ALOAD){ //ALOAD 1
								node = instructions.next();
								if (node instanceof FieldInsnNode && matchFieldNode((FieldInsnNode)node, Opcodes.GETFIELD, "eu", "b", "D")){ //GETFIELD net/minecraft/network/packet/Packet10Flying.yPosition
									node = instructions.next();
									if (node instanceof InsnNode && ((InsnNode)node).getOpcode() == Opcodes.DSUB){ //DSUB
										node = instructions.next();
										if (node instanceof VarInsnNode && ((VarInsnNode)node).var == 13 && ((VarInsnNode)node).getOpcode() == Opcodes.DSTORE){ //DSTORE 13
											target = node;
											break;
										}
									}
								}
							}
						}
					}
				}

				if (target != null){
					LdcInsnNode ldc = new LdcInsnNode(1.5);
					VarInsnNode dstore13 = new VarInsnNode(Opcodes.DSTORE, 13);

					mn.instructions.insert(target, ldc);
					mn.instructions.insert(ldc, dstore13);

					LogHelper.debug("Core: Success!  Inserted opcodes!");
				}
			}
		}

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	private boolean matchFieldNode(FieldInsnNode node, int opcode, String owner, String name, String desc){
		if (node.getOpcode() == opcode && node.owner.equals(owner) && node.name.equals(name) && node.desc.equals(desc))
			return true;
		return false;
	}

	private void debugPrintInsns(MethodNode mn){
		Iterator<AbstractInsnNode> it = mn.instructions.iterator();

		LogHelper.debug("Core: Beginning dump of Insns for %s %s", mn.name, mn.desc);

		LogHelper.debug("================================================================================");
		LogHelper.log(Level.INFO, "");

		while (it.hasNext()){
			AbstractInsnNode node = it.next();
			String className = node.toString().split("@")[0];
			className = className.substring(className.lastIndexOf(".") + 1);
			if (node instanceof VarInsnNode){
				LogHelper.log(Level.INFO, opcodeToString(node.getOpcode()) + " " + ((VarInsnNode)node).var);
			}else if (node instanceof FieldInsnNode){
				LogHelper.log(Level.INFO, opcodeToString(node.getOpcode()) + " " + ((FieldInsnNode)node).owner + "/" + ((FieldInsnNode)node).name + " (" + ((FieldInsnNode)node).desc + ")");
			}else if (node instanceof MethodInsnNode){
				LogHelper.log(Level.INFO, opcodeToString(node.getOpcode()) + " " + ((MethodInsnNode)node).owner + "/" + ((MethodInsnNode)node).name + " " + ((MethodInsnNode)node).desc);
			}else{
				LogHelper.log(Level.INFO, className + " >> " + opcodeToString(node.getOpcode()));
			}
		}

		LogHelper.debug("================================================================================");
		LogHelper.debug("Core: End");
	}

	private String opcodeToString(int opcode){
		Field[] fields = Opcodes.class.getFields();
		for (Field f : fields){
			if (f.getType() == Integer.class || f.getType() == int.class){
				try{
					if (f.getInt(null) == opcode){
						return f.getName();
					}
				}catch (Throwable t){ /* Don't care */ }
			}
		}
		return "OPCODE_UNKNOWN";
	}
}
